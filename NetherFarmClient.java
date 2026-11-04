package fr.netherfarm;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.Random;
import java.util.Set;

/**
 * NetherFarm v0.3 : parcourt un champ de nether wart en bandes de 3 blocs (houe 3x3).
 *
 * N = démarrer / arrêter.
 *
 * Au démarrage, le mod envoie /home nw, attend, puis part de là. Il avance en cassant la verrue
 * jusqu'à ce qu'il trouve un bloc de VERRE devant lui (marqueur de fin de bande), se décale de
 * 3 blocs, fait demi-tour et repart dans l'autre sens, etc.
 *
 * PRÉPARATION (une seule fois) : mets-toi en vol, à l'aplomb de la 2e colonne depuis le bord du champ,
 * tête inclinée comme pour farmer, regard dans le sens des bandes, viseur sur la première rangée,
 * puis fais /sethome nw. Le home garde position, direction et inclinaison.
 *
 * EN BOUCLE : quand toutes les bandes sont faites (après 5 s sans action), ou si le joueur ne bouge plus
 * pendant 5 s, il refait /home nw et recommence. Après 3 échecs de suite sans avoir fini une seule bande,
 * il s'arrête pour ne pas tourner dans le vide.
 *
 * Aucun téléport en dehors de ta commande /home, aucune modification de blocs.
 */
public final class NetherFarmClient implements ClientModInitializer {

    // ---- Départ ----
    private static final String HOME_COMMAND = "home nw";   // sans le "/"
    private static final int HOME_WAIT_TICKS = 60;           // 3 s d'attente après la commande

    // ---- Réglages du champ ----
    /** Nombre de bandes par étage (largeur du champ / 3). 87 / 3 = 29. */
    private static final int LANE_COUNT = 29;
    /** Nombre d'étages. */
    private static final int FLOORS = 1;
    /** Différence de hauteur de vol entre deux étages (en blocs). À ajuster ! */
    private static final double FLOOR_HEIGHT = 4.0;
    /** true = le champ s'étend vers ta droite au départ, false = vers ta gauche. */
    private static final boolean SHIFT_TO_RIGHT = false;
    /** Écart entre deux bandes = largeur de la houe 3x3. */
    private static final double LANE_SPACING = 3.0;

    // ---- Détection du verre (fin de bande) ----
    /** Le mod cherche du verre dans ta colonne, de 1 à N blocs devant toi. */
    private static final int END_LOOKAHEAD = 2;
    /** Hauteurs scannées par rapport à ton bloc : de -SCAN_DOWN à +SCAN_UP. */
    private static final int END_SCAN_DOWN = 3;
    private static final int END_SCAN_UP = 2;
    /** Sécurité : arrêt si tu t'éloignes de plus de N blocs du point de départ sans trouver de verre. */
    private static final double MAX_DISTANCE_FROM_START = 150.0;

    // ---- Boucle ----
    /** true = quand tout est fini, attend puis recommence depuis le /home. */
    private static final boolean REPEAT_WHEN_DONE = true;
    private static final int REPEAT_DELAY_TICKS = 100;       // 5 s sans action avant de retourner au /home
    /** Retours au /home de suite, sans aucune bande terminée, avant arrêt définitif. */
    private static final int MAX_BAD_RESTARTS = 3;

    // ---- Obsidienne = fin du parcours : retour au /home et nouveau tour ----
    private static final boolean OBSIDIAN_RESTART_ENABLED = true;
    private static final int OBSIDIAN_RESTART_DELAY_TICKS = 10;  // 0,5 s avant de refaire /home
    /** Distance scannée sur le côté (vers la bande suivante) pour repérer l'obsidienne. */
    private static final int OBSIDIAN_SIDE_SCAN = 4;

    // ---- Vitesse ----
    /** true = le joueur sprinte pendant les bandes (vole plus vite). */
    private static final boolean SPRINT_ENABLED = true;

    // ---- Blocs spéciaux à casser (citrouille dorée) ----
    private static final boolean SPECIAL_BREAK_ENABLED = true;
    /** Identifiants de blocs (sans "minecraft:") à casser. Touche B = affiche l'identifiant du bloc visé. */
    private static final Set<String> SPECIAL_BLOCK_IDS = Set.of(
            "pumpkin", "carved_pumpkin", "jack_o_lantern", "hay_block", "gold_block");
    /**
     * Entités à casser, par "signature" = type|largeur|hauteur, comme l'affiche la touche B
     * (exemple : "interaction|1.0|1.0"). Vide par défaut : utilise V sur une citrouille dorée,
     * puis envoie-moi la signature pour que je la mette ici définitivement.
     */
    private static final Set<String> SPECIAL_ENTITY_SIGNATURES = Set.of();
    /** Types entiers à casser (sans "minecraft:"), quelle que soit la taille. La citrouille dorée = interaction. */
    private static final Set<String> SPECIAL_ENTITY_IDS = Set.of("interaction");
    /**
     * true = toute entité CLIQUABLE (qui a une hitbox, comme la citrouille dorée) dans ta bande est ciblée,
     * sauf joueurs et objets au sol. Les affichages purement visuels sans hitbox (verrue brillante) sont ignorés.
     */
    private static final boolean SPECIAL_ANY_PICKABLE_ENTITY = false;
    /** true = toute entité sous le viseur est ciblée (déconseillé). */
    private static final boolean AUTO_CROSSHAIR_ENTITY = false;
    /** Détection anticipée : le mod repère les entités jusqu'à N blocs devant toi (et un peu derrière). */
    private static final double ENTITY_DETECT_AHEAD = 12.0;
    private static final double ENTITY_DETECT_BEHIND = 6.0;
    /** Largeur de détection de chaque côté de ta ligne (couvre un 3x3 devant toi). */
    private static final double ENTITY_DETECT_SIDE = 2.5;
    /** Zone verticale de détection par rapport à toi (évite les cibles d'un autre étage). */
    private static final double ENTITY_DETECT_DOWN = 4.5;
    private static final double ENTITY_DETECT_UP = 2.0;
    /** Ignore les cibles cachées derrière des blocs (autre étage, murs). */
    private static final boolean REQUIRE_LINE_OF_SIGHT = true;
    /** Ignore les `interaction` sans visuel à côté (entité invisible qui ne correspond à aucune citrouille). */
    private static final boolean REQUIRE_VISUAL_NEARBY = true;
    /** Affiche dans le chat chaque cible choisie (type, taille, distance) pour comprendre ce qu'il vise. */
    private static final boolean DEBUG_TARGETS = true;
    /** Temps max (ticks) passé sur une même cible, approche comprise, avant de l'abandonner. */
    private static final int BREAK_MAX_TOTAL_TICKS = 300;
    /** Le mod s'approche jusqu'à cette distance (yeux -> centre) avant de frapper (portée vanilla : 3). */
    private static final double ENTITY_APPROACH_DIST = 2.6;
    private static final int BREAK_CLICKS = 6;                 // nombre de coups
    private static final int BREAK_CLICK_INTERVAL_TICKS = 4;   // délai entre deux coups
    private static final int BREAK_MAX_ROUNDS = 2;             // séries de 6 coups max avant d'abandonner
    private static final double BREAK_REACH = 4.5;             // portée max (yeux -> centre du bloc)
    private static final int SPECIAL_SCAN_AHEAD = 5;           // blocs scannés devant toi
    private static final int SPECIAL_SCAN_DOWN = 6;            // blocs scannés sous toi
    private static final float MAX_PITCH_STEP = 8.0f;          // degrés par tick pour viser / reprendre l'inclinaison

    // ---- Mouvements plus rapides et plus naturels ----
    /** Vitesse max de rotation (degrés/tick) pour changer de direction ; varie un peu à chaque bande. */
    private static final float TURN_MAX = 28.0f;
    /** Le virage ralentit en approchant du cap (part de l'écart restant corrigée à chaque tick). */
    private static final float TURN_GAIN = 0.30f;
    private static final float TURN_MIN_STEP = 3.0f;
    /** On recommence à avancer et casser dès que le cap est bon à ce nombre de degrés près. */
    private static final float ALIGN_DEG = 10.0f;
    /** Petites variations aléatoires de la visée pendant les bandes (en degrés, 0 = aucune). */
    private static final float HUMAN_NOISE = 0.35f;

    // ---- Menus du serveur (ex. tirage de cartes) ----
    /** true = quand un menu de serveur s'ouvre pendant le farm, le chat liste son contenu (une fois). */
    private static final boolean DUMP_CONTAINER_SCREENS = true;

    // ---- Tirage de cartes automatique ----
    /** true = ferme tout menu qui s'ouvre pendant le farm, comme si tu appuyais sur Échap. */
    private static final boolean AUTO_CLOSE_MENUS = true;
    private static final int AUTO_CLOSE_MIN_TICKS = 20;      // délai minimum avant de fermer (1 s)
    private static final int AUTO_CLOSE_JITTER_TICKS = 14;   // + 0 à 0,7 s au hasard
    /** Tirage de cartes par clics : désactivé tant que la fermeture suffit. */
    private static final boolean CARD_AUTO_ENABLED = false;
    /** true = les cartes sont aussi tirées quand le farm est arrêté (menu ouvert à la main, par exemple). */
    private static final boolean CARD_WHEN_IDLE = true;
    /** true = pendant le farm, le chat annonce chaque nouvel écran ouvert (type et titre) pour le diagnostic. */
    private static final boolean DEBUG_SCREENS = true;
    private static final String VERSION_TAG = "v1.1 fermeture des menus";
    /** Mots (en minuscules) qui identifient un menu de cartes : dans le titre du menu ou le nom des cartes. */
    private static final String[] CARD_KEYWORDS = {"carte", "card", "podium"};
    /** true = clique sur le butin apparu (shift-clic) avant de fermer. */
    private static final boolean CARD_CLAIM_LOOT = true;
    /** true = ferme le menu à la fin pour que le farm reprenne tout seul. */
    private static final boolean CARD_AUTO_CLOSE = true;

    // ---- Fenêtre en arrière-plan ----
    /** true = le menu du jeu ne s'ouvre plus quand tu quittes la fenêtre, et le mod continue de tourner. */
    private static final boolean KEEP_RUNNING_IN_BACKGROUND = true;

    // ---- Commandes périodiques (en alternance) ----
    private static final boolean PERIODIC_COMMANDS_ENABLED = true;
    /** Une commande toutes les N bandes terminées. */
    private static final int COMMAND_EVERY_LANES = 10;
    private static final String COMMAND_A = "sell all";   // sans le "/"
    private static final String COMMAND_B = "feed";       // sans le "/"
    /** Courte pause après la commande, pour laisser le serveur répondre. */
    private static final int COMMAND_PAUSE_TICKS = 20;

    // ---- Sécurité / pilotage ----
    private static final float MIN_HEALTH = 6.0f;            // 3 coeurs
    private static final boolean STOP_ON_CHAT_MENTION = false;
    private static final float MAX_YAW_STEP = 10.0f;          // degrés par tick pour se retourner
    private static final double LATERAL_TOLERANCE = 0.25;
    private static final double SHIFT_THRESHOLD = 0.6;
    private static final double ALTITUDE_TOLERANCE_UP = 0.3;
    private static final double ALTITUDE_TOLERANCE_DOWN = 0.6;
    private static final int STUCK_TICKS = 100;              // 5 s sans avancer de 0,5 bloc
    private static final int LANE_TIMEOUT_TICKS = 1800;      // 90 s max par bande

    // ---- État ----
    private static boolean running = false;
    private static int waitTicks = 0;               // attente (après /home ou avant répétition)
    private static boolean sendHomeWhenWaitEnds = false;
    private static int laneCounter = 0;
    private static int laneTicks = 0;
    private static int lanesCompletedThisRun = 0;
    private static int pauseTicks = 0;
    private static boolean pauseOptionChanged = false;
    private static boolean previousPauseOnLostFocus = true;
    private static final Random RNG = new Random();
    private static float noiseYaw = 0f;
    private static float noisePitch = 0f;
    private static float turnMax = TURN_MAX;
    private static boolean breaking = false;
    private static BlockPos breakTarget = null;
    private static Entity breakEntity = null;
    private static final Set<Integer> ignoredEntities = new HashSet<>();
    private static final Set<String> learnedEntityTypes = new HashSet<>();
    private static final Set<String> learnedBlockIds = new HashSet<>();
    private static KeyMapping learnKey;
    private static KeyMapping holdKey;
    private static boolean simpleRunning = false;
    private static int breakClicksDone = 0;
    private static int breakCooldown = 0;
    private static int breakSettle = 0;
    private static int breakRounds = 0;
    private static int breakAimTicks = 0;
    private static Screen cardScreen = null;
    private static int cardPhase = 0;
    private static int cardWait = 0;
    private static int cardClicked = -1;
    private static int cardFails = 0;
    private static int cardFlips = 0;
    private static int cardLootIndex = 0;
    private static boolean cardDone = false;
    private static final List<Integer> cardSlots = new ArrayList<>();
    private static final Map<Integer, String> cardSig = new HashMap<>();
    private static final Set<Integer> cardTried = new HashSet<>();
    private static final Set<Integer> cardEmptyAtStart = new TreeSet<>();
    private static final List<Integer> cardLoot = new ArrayList<>();
    private static Screen closeScreen = null;
    private static int closeWait = 0;
    private static boolean closeDone = false;
    private static Screen debugScreen = null;
    private static Screen dumpScreen = null;
    private static int dumpTicks = 0;
    private static boolean dumped = false;
    private static int breakApproachTicks = 0;
    private static int breakTotalTicks = 0;
    private static int specialPauseTicks = 0;
    private static final Set<Long> ignoredEntityPositions = new HashSet<>();
    private static boolean chainActive = false;
    private static int chainCount = 0;
    private static final Set<BlockPos> ignoredBreak = new HashSet<>();
    private static final InputConstants.Key LEFT_MOUSE =
            InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    private static KeyMapping inspectKey;
    private static boolean nextCommandIsA = true;
    private static int badRestarts = 0;
    private static int stuckTicks = 0;
    private static double stuckAnchor = 0;
    private static int fx, fz;
    private static int sx, sz;
    private static int sgnS;
    private static double startAlong, startLat, startY;
    private static float startPitch;
    private static volatile boolean chatMention = false;

    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("netherfarm", "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.netherfarm.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));

        inspectKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.netherfarm.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));

        learnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.netherfarm.learn", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));

        holdKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.netherfarm.hold", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(NetherFarmClient::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, timestamp) -> onChatMessage(message));
    }

    // =====================================================================================

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            running = false;
            simpleRunning = false;
            restorePauseOption(mc);
            return;
        }

        while (toggleKey.consumeClick()) {
            if (running) {
                stop(mc);
                message(mc, "Arrêté.");
            } else {
                if (simpleRunning) stopSimple(mc);
                running = true;
                chatMention = false;
                laneCounter = 0;
                badRestarts = 0;
                if (KEEP_RUNNING_IN_BACKGROUND && !pauseOptionChanged) {
                    previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
                    mc.options.pauseOnLostFocus = false;
                    pauseOptionChanged = true;
                }
                sendHome(mc);
            }
        }
        while (inspectKey.consumeClick()) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                Block b = mc.level.getBlockState(hit.getBlockPos()).getBlock();
                message(mc, "Bloc visé : " + BuiltInRegistries.BLOCK.getKey(b));
            } else if (mc.hitResult instanceof EntityHitResult eh) {
                message(mc, "Entité visée : " + describeEntity(eh.getEntity()));
            } else {
                message(mc, "Rien de visé. Entités proches : " + nearbyEntitiesSummary(mc, player));
            }
        }
        while (learnKey.consumeClick()) {
            if (mc.hitResult instanceof EntityHitResult eh) {
                String sig = entitySignature(eh.getEntity());
                learnedEntityTypes.add(sig);
                message(mc, "Cible ajoutée : " + sig + " (valable jusqu'au redémarrage du jeu).");
            } else if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                String id = BuiltInRegistries.BLOCK
                        .getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).getPath();
                if (id.equals("nether_wart") || id.equals("soul_sand") || id.equals("air")) {
                    message(mc, "« " + id + " » ne peut pas être ajouté (c'est ta culture).");
                } else {
                    learnedBlockIds.add(id);
                    message(mc, "Bloc « " + id + " » ajouté aux cibles à casser (jusqu'au redémarrage du jeu).");
                }
            } else {
                message(mc, "Vise d'abord la citrouille dorée.");
            }
        }
        // Menus ouverts : diagnostic, liste du contenu, tirage de cartes (farm en cours ou non).
        if (mc.screen != null) {
            if (running && DEBUG_SCREENS) debugScreenIfNew(mc);
            if (running && DUMP_CONTAINER_SCREENS) dumpContainerIfNew(mc, player);
            if (running && AUTO_CLOSE_MENUS) autoCloseMenu(mc);
            if (CARD_AUTO_ENABLED && (running || CARD_WHEN_IDLE)) handleCardScreen(mc, player, running);
        }

        while (holdKey.consumeClick()) {
            if (simpleRunning) {
                stopSimple(mc);
                message(mc, "Mode simple arrêté.");
            } else if (running) {
                message(mc, "Le farm est en cours : arrête-le d'abord avec N.");
            } else {
                simpleRunning = true;
                if (KEEP_RUNNING_IN_BACKGROUND && !pauseOptionChanged) {
                    previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
                    mc.options.pauseOnLostFocus = false;
                    pauseOptionChanged = true;
                }
                message(mc, "Mode simple : j'avance et je casse sans fin. H pour arrêter.");
            }
        }
        if (simpleRunning) {
            simpleTick(mc, player);
            return;
        }

        if (!running) return;

        if (!player.isAlive()) {
            stop(mc);
            message(mc, "Arrêt de sécurité : joueur mort.");
            return;
        }
        if (player.getHealth() < MIN_HEALTH) {
            stop(mc);
            message(mc, "Arrêt de sécurité : vie trop basse.");
            return;
        }
        if (STOP_ON_CHAT_MENTION && chatMention) {
            stop(mc);
            message(mc, "Arrêt : un message du chat contient ton pseudo. Réponds, puis relance avec N.");
            return;
        }
        // Menu du jeu ouvert parce que la fenêtre a perdu le focus : on le ferme et on continue.
        if (KEEP_RUNNING_IN_BACKGROUND && mc.screen instanceof PauseScreen && !mc.isWindowActive()) {
            mc.setScreen(null);
        }
        if (mc.screen != null) {
            releaseKeys(mc);
            return;
        }

        // Attente : après /home (puis on initialise le parcours) ou avant une répétition.
        if (waitTicks > 0) {
            releaseKeys(mc);
            waitTicks--;
            if (waitTicks == 0) {
                if (sendHomeWhenWaitEnds) {
                    sendHome(mc);
                } else {
                    initFromPlayer(mc, player);
                }
            }
            return;
        }

        if (pauseTicks > 0) {
            releaseKeys(mc);
            pauseTicks--;
            return;
        }

        runTick(mc, player);
    }

    /** Retourne au /home après `delay` ticks et recommence. Les causes anormales sont comptées. */
    private static void restartFromHome(Minecraft mc, String reason, boolean abnormal, int delay) {
        releaseKeys(mc);
        if (abnormal) {
            badRestarts = (lanesCompletedThisRun == 0) ? badRestarts + 1 : 1;
            if (badRestarts >= MAX_BAD_RESTARTS) {
                stop(mc);
                message(mc, "Arrêt de sécurité : " + reason + ", et " + MAX_BAD_RESTARTS
                        + " retours de suite sans bande terminée. Vérifie le champ.");
                return;
            }
        } else {
            badRestarts = 0;
        }
        message(mc, reason + " : retour au /" + HOME_COMMAND + ".");
        breaking = false;
        breakTarget = null;
        breakEntity = null;
        laneCounter = 0;
        sendHomeWhenWaitEnds = true;
        waitTicks = Math.max(1, delay);
    }

    /** Envoie /sell all puis /feed puis /sell all... une à chaque appel. */
    private static void sendPeriodicCommand(Minecraft mc) {
        String cmd = nextCommandIsA ? COMMAND_A : COMMAND_B;
        nextCommandIsA = !nextCommandIsA;
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) connection.sendCommand(cmd);
        pauseTicks = COMMAND_PAUSE_TICKS;
        message(mc, "Bande " + laneCounter + " terminée : /" + cmd + " envoyé.");
    }

    /**
     * Ferme un menu qui vient de s'ouvrir, comme Échap : on appelle onClose() de l'écran, ce que fait
     * la touche Échap. Le chat, le menu pause et ton inventaire ne sont pas touchés.
     */
    private static void autoCloseMenu(Minecraft mc) {
        Screen sc = mc.screen;
        if (sc instanceof ChatScreen || sc instanceof PauseScreen || sc instanceof InventoryScreen) return;
        if (!sc.shouldCloseOnEsc()) return;   // écrans qu'Échap ne ferme pas (mort, etc.)
        if (sc != closeScreen) {
            closeScreen = sc;
            closeWait = AUTO_CLOSE_MIN_TICKS + RNG.nextInt(AUTO_CLOSE_JITTER_TICKS + 1);
            closeDone = false;
        }
        if (closeDone) return;
        if (--closeWait > 0) return;
        closeDone = true;
        message(mc, "Menu fermé (Échap) : " + sc.getClass().getSimpleName()
                + " « " + sc.getTitle().getString() + " ». Le farm reprend.");
        sc.onClose();
    }

    private static void debugScreenIfNew(Minecraft mc) {
        Screen sc = mc.screen;
        if (sc == debugScreen) return;
        debugScreen = sc;
        if (sc instanceof ChatScreen || sc instanceof PauseScreen || sc instanceof InventoryScreen) return;
        message(mc, "Écran ouvert : " + sc.getClass().getSimpleName() + " « " + sc.getTitle().getString() + " »");
    }

    private static String stackSig(ItemStack st) {
        if (st.isEmpty()) return "vide";
        return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath() + "|" + st.getHoverName().getString()
                + "|" + st.getCount();
    }

    private static boolean containsKeyword(String text) {
        for (String k : CARD_KEYWORDS) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private static void clickSlot(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu,
                                  int slot, ClickType type) {
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, player);
        }
    }

    /**
     * Tirage de cartes : repère les cartes (objets identiques, face cachée), en retourne une à la fois
     * avec des délais variables, s'arrête quand plus aucune ne se retourne, récupère le butin, ferme le menu.
     * Ne fait rien si le menu n'est pas reconnu comme un menu de cartes.
     */
    private static void handleCardScreen(Minecraft mc, LocalPlayer player, boolean verbose) {
        Screen sc = mc.screen;
        if (!(sc instanceof AbstractContainerScreen<?> cs) || sc instanceof InventoryScreen) return;
        if (sc != cardScreen) {
            cardScreen = sc;
            cardPhase = 0;
            cardWait = 15 + RNG.nextInt(8);
            cardDone = false;
            cardSlots.clear();
            cardSig.clear();
            cardTried.clear();
            cardEmptyAtStart.clear();
            cardLoot.clear();
            cardFails = 0;
            cardFlips = 0;
            cardLootIndex = 0;
        }
        if (cardDone) return;
        AbstractContainerMenu menu = cs.getMenu();

        switch (cardPhase) {
            case 0 -> {
                if (--cardWait > 0) return;
                Map<String, List<Integer>> groups = new LinkedHashMap<>();
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot sl = menu.slots.get(i);
                    if (sl.container == player.getInventory()) continue;
                    ItemStack st = sl.getItem();
                    if (st.isEmpty()) {
                        cardEmptyAtStart.add(i);
                        continue;
                    }
                    groups.computeIfAbsent(stackSig(st), k -> new ArrayList<>()).add(i);
                }
                boolean titleMatch = containsKeyword(sc.getTitle().getString().toLowerCase(Locale.ROOT));
                List<Integer> best = null;
                boolean bestKeyword = false;
                for (List<Integer> g : groups.values()) {
                    if (g.size() < 2 || g.size() > 8) continue;
                    String name = menu.slots.get(g.get(0)).getItem().getHoverName().getString()
                            .toLowerCase(Locale.ROOT);
                    if (name.isBlank()) continue;
                    boolean kw = containsKeyword(name);
                    if (best == null || (kw && !bestKeyword) || (kw == bestKeyword && g.size() > best.size())) {
                        best = g;
                        bestKeyword = kw;
                    }
                }
                if (best == null || !(titleMatch || bestKeyword)) {
                    cardDone = true;
                    if (verbose) message(mc, "Menu non reconnu comme un tirage de cartes : je te laisse la main.");
                    return;
                }
                cardSlots.addAll(best);
                for (int i : best) cardSig.put(i, stackSig(menu.slots.get(i).getItem()));
                cardPhase = 1;
                cardWait = 10 + RNG.nextInt(13);
                message(mc, "Tirage de cartes : " + best.size() + " cartes détectées, je les retourne.");
            }
            case 1 -> {
                if (--cardWait > 0) return;
                List<Integer> available = new ArrayList<>();
                for (int i : cardSlots) {
                    if (!cardTried.contains(i)
                            && stackSig(menu.slots.get(i).getItem()).equals(cardSig.get(i))) {
                        available.add(i);
                    }
                }
                if (available.isEmpty()) {
                    cardPhase = 3;
                    cardWait = 15 + RNG.nextInt(16);
                    return;
                }
                int pick = available.get(RNG.nextInt(available.size()));
                clickSlot(mc, player, menu, pick, ClickType.PICKUP);
                cardClicked = pick;
                cardPhase = 2;
                cardWait = 18 + RNG.nextInt(8);
            }
            case 2 -> {
                if (--cardWait > 0) return;
                cardTried.add(cardClicked);
                if (!stackSig(menu.slots.get(cardClicked).getItem()).equals(cardSig.get(cardClicked))) {
                    cardFlips++;
                    cardFails = 0;
                } else {
                    cardFails++;
                }
                if (cardFails >= 2) {
                    cardPhase = 3;
                    cardWait = 15 + RNG.nextInt(16);
                } else {
                    cardPhase = 1;
                    cardWait = 8 + RNG.nextInt(13);
                }
            }
            case 3 -> {
                if (--cardWait > 0) return;
                cardLoot.clear();
                if (CARD_CLAIM_LOOT) {
                    for (int i : cardEmptyAtStart) {
                        if (!menu.slots.get(i).getItem().isEmpty()) cardLoot.add(i);
                    }
                }
                cardLootIndex = 0;
                cardPhase = 4;
                cardWait = 6 + RNG.nextInt(8);
                message(mc, "Cartes retournées : " + cardFlips + ".");
            }
            case 4 -> {
                if (--cardWait > 0) return;
                if (cardLootIndex < cardLoot.size()) {
                    clickSlot(mc, player, menu, cardLoot.get(cardLootIndex++), ClickType.QUICK_MOVE);
                    cardWait = 6 + RNG.nextInt(9);
                    return;
                }
                cardPhase = 5;
                cardWait = 20 + RNG.nextInt(15);
            }
            default -> {
                if (--cardWait > 0) return;
                cardDone = true;
                if (CARD_AUTO_CLOSE) {
                    message(mc, "Tirage terminé : je ferme le menu et je reprends le farm.");
                    player.closeContainer();
                } else {
                    message(mc, "Tirage terminé : ferme le menu pour reprendre le farm.");
                }
            }
        }
    }

    /** Liste (une fois) le contenu d'un menu de serveur : emplacement, objet, nom. Pour configurer le tirage. */
    private static void dumpContainerIfNew(Minecraft mc, LocalPlayer player) {
        Screen sc = mc.screen;
        if (!(sc instanceof AbstractContainerScreen<?> cs) || sc instanceof InventoryScreen) return;
        if (sc != dumpScreen) {
            dumpScreen = sc;
            dumpTicks = 0;
            dumped = false;
        }
        if (dumped) return;
        if (++dumpTicks < 12) return;   // le serveur envoie le contenu quelques ticks après l'ouverture
        dumped = true;

        AbstractContainerMenu menu = cs.getMenu();
        message(mc, "Menu ouvert : « " + sc.getTitle().getString() + " » (" + menu.slots.size() + " emplacements).");
        StringBuilder line = new StringBuilder();
        int inLine = 0;
        int total = 0;
        for (int i = 0; i < menu.slots.size() && total < 54; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) continue;   // on ignore ton inventaire
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (line.length() > 0) line.append(" ; ");
            line.append("#").append(i).append(" ")
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                    .append(" « ").append(stack.getHoverName().getString()).append(" » x").append(stack.getCount());
            total++;
            if (++inLine == 4) {
                message(mc, line.toString());
                line.setLength(0);
                inLine = 0;
            }
        }
        if (line.length() > 0) message(mc, line.toString());
        if (total == 0) message(mc, "Aucun objet dans ce menu.");
    }

    private static void sendHome(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) connection.sendCommand(HOME_COMMAND);
        sendHomeWhenWaitEnds = false;
        waitTicks = HOME_WAIT_TICKS;
        message(mc, "/" + HOME_COMMAND + " envoyé, départ dans " + (HOME_WAIT_TICKS / 20) + " s.");
    }

    /** Prend la position / direction / inclinaison actuelles (juste après le /home) comme départ. */
    private static void initFromPlayer(Minecraft mc, LocalPlayer player) {
        Direction d = player.getDirection();
        fx = d.getStepX();
        fz = d.getStepZ();
        sgnS = SHIFT_TO_RIGHT ? 1 : -1;
        sx = sgnS * (-fz);
        sz = sgnS * fx;

        BlockPos bp = player.blockPosition();
        double cx = bp.getX() + 0.5;
        double cz = bp.getZ() + 0.5;
        startAlong = cx * fx + cz * fz;
        startLat = cx * sx + cz * sz;
        startY = player.position().y;
        startPitch = player.getXRot();

        laneCounter = 0;
        laneTicks = 0;
        lanesCompletedThisRun = 0;
        pauseTicks = 0;
        turnMax = TURN_MAX;
        breaking = false;
        breakTarget = null;
        breakEntity = null;
        chainActive = false;
        ignoredBreak.clear();
        ignoredEntities.clear();
        ignoredEntityPositions.clear();
        specialPauseTicks = 0;
        stuckTicks = 0;
        stuckAnchor = startAlong;
        message(mc, "Parcours lancé : " + LANE_COUNT + " bandes x " + FLOORS
                + " étage(s), fin de bande = bloc de verre. [" + VERSION_TAG + "]");
    }

    private static void runTick(Minecraft mc, LocalPlayer player) {
        int totalLanes = LANE_COUNT * FLOORS;
        if (laneCounter >= totalLanes) {
            releaseKeys(mc);
            if (REPEAT_WHEN_DONE) {
                restartFromHome(mc, "Terminé", false, REPEAT_DELAY_TICKS);
            } else {
                stop(mc);
                message(mc, "Terminé : " + totalLanes + " bandes parcourues.");
            }
            return;
        }

        laneTicks++;
        if (laneTicks > LANE_TIMEOUT_TICKS) {
            restartFromHome(mc, "Bande trop longue (bande " + (laneCounter + 1) + ")", true, 1);
            return;
        }

        int floor = laneCounter / LANE_COUNT;
        int idx = laneCounter % LANE_COUNT;
        int dirSign = (laneCounter % 2 == 0) ? 1 : -1;
        int latIndex = (floor % 2 == 0) ? idx : (LANE_COUNT - 1 - idx);
        double targetLat = startLat + LANE_SPACING * latIndex;
        double targetY = startY + FLOOR_HEIGHT * floor;

        Vec3 pos = player.position();
        double along = pos.x * fx + pos.z * fz;
        double lat = pos.x * sx + pos.z * sz;
        double dy = targetY - pos.y;

        if (Math.abs(along - startAlong) > MAX_DISTANCE_FROM_START) {
            restartFromHome(mc, "Trop loin du départ sans verre (bande " + (laneCounter + 1) + ")", true, 1);
            return;
        }

        // Obsidienne repérée : fin du parcours, on recommence depuis le /home.
        if (OBSIDIAN_RESTART_ENABLED && obsidianNearby(mc, pos, dirSign)) {
            restartFromHome(mc, "Obsidienne détectée", lanesCompletedThisRun == 0,
                    OBSIDIAN_RESTART_DELAY_TICKS);
            return;
        }

        // Bloc spécial (citrouille dorée) : on s'arrête, on vise, on tape 6 fois, puis on reprend.
        if (SPECIAL_BREAK_ENABLED) {
            if (breaking) {
                handleBreak(mc, player);
                return;
            }
            BlockPos found = null;
            Entity foundEntity = null;
            if (specialPauseTicks > 0) {
                specialPauseTicks--;
            } else {
                found = findSpecialBlock(mc, player, pos, dirSign);
                if (found == null) foundEntity = findSpecialEntity(mc, player, pos, dirSign);
            }
            // Automatique : toute entité qui se trouve sous le viseur (hors joueurs et objets au sol).
            if (AUTO_CROSSHAIR_ENTITY && found == null && foundEntity == null
                    && mc.hitResult instanceof EntityHitResult eh) {
                Entity hitE = eh.getEntity();
                if (!(hitE instanceof Player) && !(hitE instanceof ItemEntity)
                        && !hitE.isRemoved() && !ignoredEntities.contains(hitE.getId())) {
                    foundEntity = hitE;
                }
            }
            if (found != null || foundEntity != null) {
                if (!chainActive) {
                    chainActive = true;
                    chainCount = 0;
                    message(mc, "Cible(s) repérée(s) : j'y vais (" + BREAK_CLICKS + " coups chacune).");
                }
                chainCount++;
                if (DEBUG_TARGETS) {
                    message(mc, "Cible : " + (foundEntity != null
                            ? describeEntity(foundEntity) + " à "
                                    + String.format(Locale.ROOT, "%.1f", foundEntity.distanceTo(player)) + " blocs"
                            : "bloc " + BuiltInRegistries.BLOCK
                                    .getKey(mc.level.getBlockState(found).getBlock())));
                }
                breaking = true;
                breakTarget = found;
                breakEntity = foundEntity;
                breakClicksDone = 0;
                breakCooldown = 0;
                breakSettle = 0;
                breakRounds = 0;
                breakAimTicks = 0;
                breakApproachTicks = 0;
                breakTotalTicks = 0;
                handleBreak(mc, player);
                return;
            } else if (chainActive) {
                chainActive = false;
                message(mc, chainCount + " cible(s) traitée(s), je reprends le parcours.");
            }
        }

        // Reprise en douceur de l'inclinaison de départ.
        noiseYaw = Mth.clamp(noiseYaw * 0.92f + (RNG.nextFloat() - 0.5f) * 0.12f, -HUMAN_NOISE, HUMAN_NOISE);
        noisePitch = Mth.clamp(noisePitch * 0.92f + (RNG.nextFloat() - 0.5f) * 0.12f, -HUMAN_NOISE, HUMAN_NOISE);
        float wantPitchNow = startPitch + noisePitch;
        player.setXRot(player.getXRot()
                + Mth.clamp(wantPitchNow - player.getXRot(), -MAX_PITCH_STEP, MAX_PITCH_STEP));

        // Montée vers l'étage suivant : on reste en place et on monte.
        if (dy > ALTITUDE_TOLERANCE_UP) {
            setKeys(mc, false, false, false, true, false, false);
            stuckTicks = 0;
            stuckAnchor = along;
            return;
        }

        // Cap de cette bande : virage souple (rapide au début, doux à la fin).
        float wantYaw = (float) Math.toDegrees(Math.atan2(-(double) (dirSign * fx), (double) (dirSign * fz)));
        float dYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
        if (Math.abs(dYaw) > 1.0f) {
            float step = Mth.clamp(dYaw * TURN_GAIN, -turnMax, turnMax);
            if (Math.abs(step) < TURN_MIN_STEP) {
                step = Math.signum(dYaw) * Math.min(TURN_MIN_STEP, Math.abs(dYaw));
            }
            player.setYRot(player.getYRot() + step);
        }
        float yawErr = Math.abs(Mth.wrapDegrees(wantYaw - player.getYRot()));

        double e = targetLat - lat;
        double rightComponent = e * dirSign * sgnS;

        // Décalage vers la bande suivante, PENDANT le virage : on ne casse pas, on glisse de côté.
        // Les touches sont calculées avec l'orientation réelle du joueur à cet instant.
        if (Math.abs(e) > SHIFT_THRESHOLD) {
            double yawRad = Math.toRadians(player.getYRot());
            double dirX = Math.signum(e) * sx;
            double dirZ = Math.signum(e) * sz;
            double fwdDot = dirX * (-Math.sin(yawRad)) + dirZ * Math.cos(yawRad);
            double rightDot = dirX * (-Math.cos(yawRad)) + dirZ * (-Math.sin(yawRad));
            setKeysFull(mc, fwdDot > 0.35, fwdDot < -0.35, rightDot < -0.35, rightDot > 0.35,
                    false, false, false);
            stuckTicks = 0;
            stuckAnchor = along;
            return;
        }

        // Fin du virage : on attend que le cap soit bon, sans clic.
        if (yawErr > ALIGN_DEG) {
            setKeys(mc, false, false, false, false, false, false);
            stuckTicks = 0;
            stuckAnchor = along;
            return;
        }

        // Fin de bande : du verre devant -> bande suivante.
        if (glassAhead(mc, pos, dirSign)) {
            laneCounter++;
            lanesCompletedThisRun++;
            laneTicks = 0;
            stuckTicks = 0;
            stuckAnchor = along;
            turnMax = TURN_MAX * (0.85f + RNG.nextFloat() * 0.30f);
            setKeys(mc, false, false, false, false, false, false);
            if (PERIODIC_COMMANDS_ENABLED && laneCounter % COMMAND_EVERY_LANES == 0
                    && laneCounter < totalLanes) {
                sendPeriodicCommand(mc);
            }
            return;
        }

        // Avance en cassant, avec petite correction de dérive et d'altitude.
        if (yawErr <= 1.0f) {
            player.setYRot(wantYaw + noiseYaw);
        }
        boolean strafeRight = rightComponent > LATERAL_TOLERANCE;
        boolean strafeLeft = rightComponent < -LATERAL_TOLERANCE;
        boolean descend = dy < -ALTITUDE_TOLERANCE_DOWN;
        setKeys(mc, true, strafeLeft, strafeRight, false, descend, true);

        if (Math.abs(along - stuckAnchor) > 0.5) {
            stuckAnchor = along;
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks > STUCK_TICKS) {
                restartFromHome(mc, "Plus d'action depuis 5 s (bande " + (laneCounter + 1) + ")", true, 1);
            }
        }
    }

    // =====================================================================================

    private static boolean isSpecial(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return SPECIAL_BLOCK_IDS.contains(path) || learnedBlockIds.contains(path);
    }

    /** Cherche un bloc spécial dans ta bande (3 de large), devant et sous toi, à portée de coups. */
    private static BlockPos findSpecialBlock(Minecraft mc, LocalPlayer player, Vec3 pos, int dirSign) {
        Vec3 eye = player.getEyePosition();
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y);
        int bz = Mth.floor(pos.z);
        int stepX = dirSign * fx;
        int stepZ = dirSign * fz;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int n = -1; n <= SPECIAL_SCAN_AHEAD; n++) {
            for (int m = -1; m <= 1; m++) {
                for (int dyb = -SPECIAL_SCAN_DOWN; dyb <= 1; dyb++) {
                    BlockPos bp = new BlockPos(bx + stepX * n + sx * m, by + dyb, bz + stepZ * n + sz * m);
                    if (ignoredBreak.contains(bp)) continue;
                    if (!isSpecial(mc.level.getBlockState(bp).getBlock())) continue;
                    double dx = bp.getX() + 0.5 - eye.x;
                    double dy = bp.getY() + 0.5 - eye.y;
                    double dz = bp.getZ() + 0.5 - eye.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist <= BREAK_REACH && dist < bestDist) {
                        bestDist = dist;
                        best = bp;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isSpecialEntity(Entity e) {
        if (e instanceof Player || e instanceof ItemEntity || e.isRemoved()
                || ignoredEntities.contains(e.getId())
                || ignoredEntityPositions.contains(e.blockPosition().asLong())) return false;
        // Sans hitbox (affichage visuel seulement, ex. verrue brillante) : on ignore.
        if (!e.isPickable()) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (SPECIAL_ENTITY_IDS.contains(id)) return true;
        String sig = entitySignature(e);
        if (SPECIAL_ENTITY_SIGNATURES.contains(sig) || learnedEntityTypes.contains(sig)) return true;
        return SPECIAL_ANY_PICKABLE_ENTITY;
    }

    /** Cherche une entité spéciale dans ta bande (3 de large), devant et sous toi, à portée de coups. */
    private static Entity findSpecialEntity(Minecraft mc, LocalPlayer player, Vec3 pos, int dirSign) {
        Vec3 eye = player.getEyePosition();
        int stepX = dirSign * fx;
        int stepZ = dirSign * fz;
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || !isSpecialEntity(e)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double rx = c.x - pos.x;
            double rz = c.z - pos.z;
            double forward = rx * stepX + rz * stepZ;
            double lateral = rx * sx + rz * sz;
            double vertical = c.y - pos.y;
            if (forward < -ENTITY_DETECT_BEHIND || forward > ENTITY_DETECT_AHEAD) continue;
            if (Math.abs(lateral) > ENTITY_DETECT_SIDE) continue;
            if (vertical < -ENTITY_DETECT_DOWN || vertical > ENTITY_DETECT_UP) continue;
            if (REQUIRE_LINE_OF_SIGHT && !player.hasLineOfSight(e)) continue;
            if (REQUIRE_VISUAL_NEARBY && !hasVisualNearby(mc, e)) continue;
            double dx = c.x - eye.x;
            double dy = c.y - eye.y;
            double dz = c.z - eye.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /** Une autre entité (affichage, support d'armure...) à moins de ~1 bloc : le visuel de la citrouille. */
    private static boolean hasVisualNearby(Minecraft mc, Entity e) {
        Vec3 p = e.position();
        for (Entity other : mc.level.entitiesForRendering()) {
            if (other == e || other instanceof Player || other.getType() == e.getType()) continue;
            Vec3 q = other.position();
            double dx = q.x - p.x;
            double dz = q.z - p.z;
            if (dx * dx + dz * dz <= 1.44 && Math.abs(q.y - p.y) <= 2.0) return true;
        }
        return false;
    }

    /** Signature d'une entité : type|largeur|hauteur (une décimale). */
    private static String entitySignature(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()
                + "|" + String.format(Locale.ROOT, "%.1f", e.getBbWidth())
                + "|" + String.format(Locale.ROOT, "%.1f", e.getBbHeight());
    }

    private static String describeEntity(Entity e) {
        StringBuilder sb = new StringBuilder(entitySignature(e));
        sb.append(e.isPickable() ? " cliquable" : " non-cliquable");
        if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("interaction")) {
            sb.append(hasVisualNearby(Minecraft.getInstance(), e) ? " visuel=oui" : " visuel=non");
        }
        if (e.isCurrentlyGlowing()) sb.append(" brille");
        if (e.getCustomName() != null) sb.append(" nom=").append(e.getCustomName().getString());
        return sb.toString();
    }

    /** Les entités les plus proches (rayon 8 blocs), avec leur signature, pour identifier la citrouille. */
    private static String nearbyEntitiesSummary(Minecraft mc, LocalPlayer player) {
        java.util.List<Entity> list = new java.util.ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || e instanceof Player) continue;
            if (e.distanceTo(player) > 8.0f) continue;
            list.add(e);
        }
        if (list.isEmpty()) return "aucune";
        list.sort(java.util.Comparator.comparingDouble(en -> en.distanceTo(player)));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entity e : list) {
            if (n++ >= 6) break;
            if (sb.length() > 0) sb.append(" ; ");
            sb.append(describeEntity(e));
        }
        return sb.toString();
    }

    /** Un tick de la séquence : viser le bloc, taper BREAK_CLICKS fois, vérifier, recommencer si besoin. */
    private static void handleBreak(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);

        breakTotalTicks++;
        if (breakTotalTicks > BREAK_MAX_TOTAL_TICKS) {
            finishBreak(mc, false);
            specialPauseTicks = 100;   // 5 s sans rechercher de cible, pour ne pas rester bloqué
            return;
        }

        Vec3 center;
        if (breakEntity != null) {
            if (breakEntity.isRemoved() || !breakEntity.isAlive()) {
                finishBreak(mc, true);
                return;
            }
            center = breakEntity.getBoundingBox().getCenter();
        } else {
            BlockState st = mc.level.getBlockState(breakTarget);
            if (st.isAir() || !isSpecial(st.getBlock())) {
                finishBreak(mc, true);
                return;
            }
            center = new Vec3(breakTarget.getX() + 0.5, breakTarget.getY() + 0.5, breakTarget.getZ() + 0.5);
        }

        // Visée vers le centre de la cible.
        Vec3 eye = player.getEyePosition();
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
        float dPitch = wantPitch - player.getXRot();
        player.setYRot(player.getYRot() + Mth.clamp(dYaw, -MAX_YAW_STEP, MAX_YAW_STEP));
        player.setXRot(player.getXRot() + Mth.clamp(dPitch, -MAX_PITCH_STEP, MAX_PITCH_STEP));
        // Entité trop loin pour être frappée : on s'en approche en la gardant dans le viseur.
        if (breakEntity != null) {
            double hd = Math.sqrt(dx * dx + dz * dz);
            double dist3 = Math.sqrt(hd * hd + dy * dy);
            if (dist3 > ENTITY_APPROACH_DIST) {
                breakApproachTicks++;
                if (breakApproachTicks > 60) {
                    finishBreak(mc, false);
                    return;
                }
                setKeysFull(mc, hd > 1.2, false, false, false, false, dy < -1.8, false);
                mc.options.keySprint.setDown(false);
                return;
            }
        }
        boolean aligned = Math.abs(Mth.wrapDegrees(wantYaw - player.getYRot())) < 1.5f
                && Math.abs(wantPitch - player.getXRot()) < 1.5f;
        if (!aligned) return;

        // Le viseur doit vraiment tomber sur le bloc (pas sur une verrue devant).
        boolean onTarget;
        if (breakEntity != null) {
            onTarget = mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == breakEntity;
        } else {
            onTarget = mc.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(breakTarget);
        }
        if (!onTarget) {
            breakAimTicks++;
            if (breakAimTicks > 40) finishBreak(mc, false);
            return;
        }
        breakAimTicks = 0;

        if (breakCooldown > 0) {
            breakCooldown--;
            return;
        }
        if (breakClicksDone < BREAK_CLICKS) {
            KeyMapping.click(LEFT_MOUSE);
            breakClicksDone++;
            breakCooldown = BREAK_CLICK_INTERVAL_TICKS;
            return;
        }
        // Série terminée : on laisse 10 ticks au serveur pour répondre, puis on recommence si le bloc est là.
        if (breakSettle < 10) {
            breakSettle++;
            return;
        }
        breakRounds++;
        if (breakRounds >= BREAK_MAX_ROUNDS) {
            finishBreak(mc, false);
        } else {
            breakClicksDone = 0;
            breakSettle = 0;
        }
    }

    private static void finishBreak(Minecraft mc, boolean success) {
        if (!success) {
            if (breakEntity != null) {
                ignoredEntities.add(breakEntity.getId());
                ignoredEntityPositions.add(breakEntity.blockPosition().asLong());
            }
            else if (breakTarget != null) ignoredBreak.add(breakTarget);
        }
        if (!success) message(mc, "Cible non cassée, je l'ignore.");
        breaking = false;
        breakTarget = null;
        breakEntity = null;
        stuckTicks = 0;
    }

    /** Du verre dans ta colonne, 1 à END_LOOKAHEAD blocs devant (dans le sens de déplacement) ? */
    private static boolean glassAhead(Minecraft mc, Vec3 pos, int dirSign) {
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y);
        int bz = Mth.floor(pos.z);
        int stepX = dirSign * fx;
        int stepZ = dirSign * fz;
        for (int n = 1; n <= END_LOOKAHEAD; n++) {
            for (int dyb = -END_SCAN_DOWN; dyb <= END_SCAN_UP; dyb++) {
                BlockPos bp = new BlockPos(bx + stepX * n, by + dyb, bz + stepZ * n);
                if (isGlass(mc.level.getBlockState(bp).getBlock())) return true;
            }
        }
        return false;
    }

    /** Obsidienne devant toi dans la bande, ou sur le côté (là où se trouve la bande suivante). */
    private static boolean obsidianNearby(Minecraft mc, Vec3 pos, int dirSign) {
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y);
        int bz = Mth.floor(pos.z);
        int stepX = dirSign * fx;
        int stepZ = dirSign * fz;
        for (int dyb = -END_SCAN_DOWN; dyb <= END_SCAN_UP; dyb++) {
            for (int n = 1; n <= END_LOOKAHEAD; n++) {
                if (isObsidian(mc.level.getBlockState(
                        new BlockPos(bx + stepX * n, by + dyb, bz + stepZ * n)).getBlock())) return true;
            }
            for (int n = 1; n <= OBSIDIAN_SIDE_SCAN; n++) {
                if (isObsidian(mc.level.getBlockState(
                        new BlockPos(bx + sx * n, by + dyb, bz + sz * n)).getBlock())) return true;
            }
        }
        return false;
    }

    private static boolean isObsidian(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.equals("obsidian") || path.equals("crying_obsidian");
    }

    private static boolean isGlass(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.equals("glass") || path.endsWith("_glass")
                || path.equals("glass_pane") || path.endsWith("_glass_pane");
    }

    private static void setKeys(Minecraft mc, boolean forward, boolean left, boolean right,
                                boolean up, boolean down, boolean attack) {
        setKeysFull(mc, forward, false, left, right, up, down, attack);
    }

    private static void setKeysFull(Minecraft mc, boolean forward, boolean back, boolean left, boolean right,
                                    boolean up, boolean down, boolean attack) {
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(SPRINT_ENABLED && forward);
        mc.options.keyDown.setDown(back);
        mc.options.keyLeft.setDown(left);
        mc.options.keyRight.setDown(right);
        mc.options.keyJump.setDown(up);
        mc.options.keyShift.setDown(down);
        mc.options.keyAttack.setDown(attack);
    }

    /** Mode simple : avance et casse sans fin, dans la direction où tu regardes. */
    private static void simpleTick(Minecraft mc, LocalPlayer player) {
        if (!player.isAlive()) {
            stopSimple(mc);
            message(mc, "Mode simple arrêté : joueur mort.");
            return;
        }
        if (player.getHealth() < MIN_HEALTH) {
            stopSimple(mc);
            message(mc, "Mode simple arrêté : vie trop basse.");
            return;
        }
        if (KEEP_RUNNING_IN_BACKGROUND && mc.screen instanceof PauseScreen && !mc.isWindowActive()) {
            mc.setScreen(null);
        }
        if (mc.screen != null) {   // inventaire, chat, menu : on lâche les touches, on reprend à la fermeture
            releaseKeys(mc);
            return;
        }
        setKeys(mc, true, false, false, false, false, true);
    }

    private static void stopSimple(Minecraft mc) {
        simpleRunning = false;
        restorePauseOption(mc);
        releaseKeys(mc);
    }

    private static void restorePauseOption(Minecraft mc) {
        if (pauseOptionChanged) {
            mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
            pauseOptionChanged = false;
        }
    }

    private static void stop(Minecraft mc) {
        running = false;
        restorePauseOption(mc);
        waitTicks = 0;
        pauseTicks = 0;
        breaking = false;
        breakTarget = null;
        breakEntity = null;
        chainActive = false;
        sendHomeWhenWaitEnds = false;
        chatMention = false;
        releaseKeys(mc);
    }

    private static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    private static void onChatMessage(Component message) {
        if (!STOP_ON_CHAT_MENTION || !running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String name = mc.player.getName().getString().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && message.getString().toLowerCase(Locale.ROOT).contains(name)) {
            chatMention = true;
        }
    }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[NetherFarm] " + text));
        }
    }
}
