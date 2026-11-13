package com.bridgecore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Network registration (NeoForge 1.21.1 payload API). */
public final class BCNetworking {
    private BCNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");
        registrar.playToServer(FireControlGroupNamePayload.TYPE, FireControlGroupNamePayload.STREAM_CODEC,
                FireControlGroupNamePayload::handle);
        registrar.playToServer(FireControlTurretNamePayload.TYPE, FireControlTurretNamePayload.STREAM_CODEC,
                FireControlTurretNamePayload::handle);
        registrar.playToServer(FireControlSectorPayload.TYPE, FireControlSectorPayload.STREAM_CODEC,
                FireControlSectorPayload::handle);
        var explicitSections = registrar.versioned("5");
        explicitSections.playToClient(FireControlIdentityPayload.TYPE, FireControlIdentityPayload.STREAM_CODEC,
                FireControlIdentityPayload::handle);
        registrar.playToServer(FireControlStrikeRequest.TYPE, FireControlStrikeRequest.STREAM_CODEC,
                FireControlStrikeRequest::handle);
        registrar.playToClient(FireControlStrikeResult.TYPE, FireControlStrikeResult.STREAM_CODEC,
                FireControlStrikeResult::handle);
        registrar.playToClient(CanvasSyncPayload.TYPE, CanvasSyncPayload.STREAM_CODEC,
                CanvasSyncPayload::handle);
        registrar.playToClient(DisplayTileSyncPayload.TYPE, DisplayTileSyncPayload.STREAM_CODEC,
                DisplayTileSyncPayload::handle);
        registrar.playToClient(DisplayGpuOpenPayload.TYPE, DisplayGpuOpenPayload.STREAM_CODEC,
                DisplayGpuOpenPayload::handle);
        registrar.playToServer(DisplayGpuSelectPayload.TYPE, DisplayGpuSelectPayload.STREAM_CODEC,
                DisplayGpuSelectPayload::handle);
        registrar.playToServer(GamepadInputPayload.TYPE, GamepadInputPayload.STREAM_CODEC,
                GamepadInputPayload::handle);
        registrar.playToServer(ExtractorRatePayload.TYPE, ExtractorRatePayload.STREAM_CODEC,
                ExtractorRatePayload::handle);
        registrar.playToServer(CoreGearMultsPayload.TYPE, CoreGearMultsPayload.STREAM_CODEC,
                CoreGearMultsPayload::handle);
        registrar.playToServer(CoreKeyBindPayload.TYPE, CoreKeyBindPayload.STREAM_CODEC,
                CoreKeyBindPayload::handle);
        registrar.playToServer(CoreHudCalibPayload.TYPE, CoreHudCalibPayload.STREAM_CODEC,
                CoreHudCalibPayload::handle);
        registrar.playToServer(CoreTuningsPayload.TYPE, CoreTuningsPayload.STREAM_CODEC,
                CoreTuningsPayload::handle);
        registrar.playToServer(CoreTogglePayload.TYPE, CoreTogglePayload.STREAM_CODEC,
                CoreTogglePayload::handle);
        registrar.playToServer(CorePadBindPayload.TYPE, CorePadBindPayload.STREAM_CODEC,
                CorePadBindPayload::handle);
        // 导航终端指令链（2026-11-08）：版本化可扩展协议，定向单包上行。
        registrar.playToServer(TerminalLinkPayload.TYPE, TerminalLinkPayload.STREAM_CODEC,
                TerminalLinkPayload::handle);
        explicitSections.playToClient(TerminalReplyPayload.TYPE, TerminalReplyPayload.STREAM_CODEC,
                TerminalReplyPayload::handle);
        registrar.playToClient(TerminalKeyMapPayload.TYPE, TerminalKeyMapPayload.STREAM_CODEC,
                TerminalKeyMapPayload::handle);
        registrar.playToServer(TerminalNudgePayload.TYPE, TerminalNudgePayload.STREAM_CODEC,
                TerminalNudgePayload::handle);
        registrar.playToServer(TerminalManeuverPayload.TYPE, TerminalManeuverPayload.STREAM_CODEC,
                TerminalManeuverPayload::handle);
        registrar.playToServer(TerminalCruisePayload.TYPE, TerminalCruisePayload.STREAM_CODEC,
                TerminalCruisePayload::handle);
        var terminalStrike = registrar.versioned("5");
        terminalStrike.playToServer(TerminalStrikeRequest.TYPE, TerminalStrikeRequest.STREAM_CODEC,
                TerminalStrikeRequest::handle);
        terminalStrike.playToClient(TerminalStrikeSnapshot.TYPE, TerminalStrikeSnapshot.STREAM_CODEC,
                TerminalStrikeSnapshot::handle);
        // 2026-10-30 落地②: FireVolleyPayload 删除 — H 收编为打字机第 20
        // 通道 (CH_FIRE), 单击/按住/双击状态机在飞控核, 直调火控 hPress。
    }
}
