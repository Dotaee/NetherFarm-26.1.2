package fr.netherfarm;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Locale;
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
    private static final int BREAK_CLICKS = 6;                 // nombre de coups
    private static final int BREAK_CLICK_INTERVAL_TICKS = 4;   // délai entre deux coups
    private static final int BREAK_MAX_ROUNDS = 3;             // séries de 6 coups max avant d'abandonner
    private static final double BREAK_REACH = 4.5;             // portée max (yeux -> centre du bloc)
    private static final int SPECIAL_SCAN_AHEAD = 5;           // blocs scannés devant toi
    private static final int SPECIAL_SCAN_DOWN = 6;            // blocs scannés sous toi
    private static final float MAX_PITCH_STEP = 8.0f;          // degrés par tick pour viser / reprendre l'inclinaison

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
    private static final boolean STOP_ON_CHAT_MENTION = true;
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
    private static boolean breaking = false;
    private static BlockPos breakTarget = null;
    private static int breakClicksDone = 0;
    private static int breakCooldown = 0;
    private static int breakSettle = 0;
    private static int breakRounds = 0;
    private static int breakAimTicks = 0;
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
            restorePauseOption(mc);
            return;
        }

        while (toggleKey.consumeClick()) {
            if (running) {
                stop(mc);
                message(mc, "Arrêté.");
            } else {
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
            } else {
                message(mc, "Aucun bloc visé.");
            }
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
        breaking = false;
        breakTarget = null;
        ignoredBreak.clear();
        stuckTicks = 0;
        stuckAnchor = startAlong;
        message(mc, "Parcours lancé : " + LANE_COUNT + " bandes x " + FLOORS
                + " étage(s), fin de bande = bloc de verre.");
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
            BlockPos found = findSpecialBlock(mc, player, pos, dirSign);
            if (found != null) {
                breaking = true;
                breakTarget = found;
                breakClicksDone = 0;
                breakCooldown = 0;
                breakSettle = 0;
                breakRounds = 0;
                breakAimTicks = 0;
                message(mc, "Bloc spécial repéré : je le casse (" + BREAK_CLICKS + " coups).");
                handleBreak(mc, player);
                return;
            }
        }

        // Reprise en douceur de l'inclinaison de départ.
        player.setXRot(player.getXRot()
                + Mth.clamp(startPitch - player.getXRot(), -MAX_PITCH_STEP, MAX_PITCH_STEP));

        // Montée vers l'étage suivant : on reste en place et on monte.
        if (dy > ALTITUDE_TOLERANCE_UP) {
            setKeys(mc, false, false, false, true, false, false);
            stuckTicks = 0;
            stuckAnchor = along;
            return;
        }

        // Demi-tour vers le sens de cette bande.
        float wantYaw = (float) Math.toDegrees(Math.atan2(-(double) (dirSign * fx), (double) (dirSign * fz)));
        float dYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
        player.setYRot(player.getYRot() + Mth.clamp(dYaw, -MAX_YAW_STEP, MAX_YAW_STEP));
        if (Math.abs(Mth.wrapDegrees(wantYaw - player.getYRot())) > 2.0f) {
            setKeys(mc, false, false, false, false, false, false);
            stuckTicks = 0;
            stuckAnchor = along;
            return;
        }

        double e = targetLat - lat;
        double rightComponent = e * dirSign * sgnS;

        // Décalage vers la bande suivante : on ne casse pas, on se déplace de côté.
        if (Math.abs(e) > SHIFT_THRESHOLD) {
            setKeys(mc, false, rightComponent < 0, rightComponent > 0, false, false, false);
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
            setKeys(mc, false, false, false, false, false, false);
            if (PERIODIC_COMMANDS_ENABLED && laneCounter % COMMAND_EVERY_LANES == 0
                    && laneCounter < totalLanes) {
                sendPeriodicCommand(mc);
            }
            return;
        }

        // Avance en cassant, avec petite correction de dérive et d'altitude.
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
        return SPECIAL_BLOCK_IDS.contains(BuiltInRegistries.BLOCK.getKey(block).getPath());
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

    /** Un tick de la séquence : viser le bloc, taper BREAK_CLICKS fois, vérifier, recommencer si besoin. */
    private static void handleBreak(Minecraft mc, LocalPlayer player) {
        releaseKeys(mc);

        BlockState st = mc.level.getBlockState(breakTarget);
        if (st.isAir() || !isSpecial(st.getBlock())) {
            finishBreak(mc, true);
            return;
        }

        // Visée vers le centre du bloc.
        Vec3 eye = player.getEyePosition();
        double dx = breakTarget.getX() + 0.5 - eye.x;
        double dy = breakTarget.getY() + 0.5 - eye.y;
        double dz = breakTarget.getZ() + 0.5 - eye.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = Mth.wrapDegrees(wantYaw - player.getYRot());
        float dPitch = wantPitch - player.getXRot();
        player.setYRot(player.getYRot() + Mth.clamp(dYaw, -MAX_YAW_STEP, MAX_YAW_STEP));
        player.setXRot(player.getXRot() + Mth.clamp(dPitch, -MAX_PITCH_STEP, MAX_PITCH_STEP));
        boolean aligned = Math.abs(Mth.wrapDegrees(wantYaw - player.getYRot())) < 1.5f
                && Math.abs(wantPitch - player.getXRot()) < 1.5f;
        if (!aligned) return;

        // Le viseur doit vraiment tomber sur le bloc (pas sur une verrue devant).
        boolean onTarget = mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(breakTarget);
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
        if (!success && breakTarget != null) ignoredBreak.add(breakTarget);
        message(mc, success ? "Bloc cassé, je reprends le parcours."
                : "Bloc non cassé, je l'ignore et je reprends le parcours.");
        breaking = false;
        breakTarget = null;
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
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(SPRINT_ENABLED && forward);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(left);
        mc.options.keyRight.setDown(right);
        mc.options.keyJump.setDown(up);
        mc.options.keyShift.setDown(down);
        mc.options.keyAttack.setDown(attack);
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
