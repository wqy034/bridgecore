package com.bridgecore.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.bridgecore.firecontrol.FireControlUiText;
import com.bridgecore.firecontrol.GimbalMath;
import com.bridgecore.firecontrol.SectorArc;
import com.bridgecore.firecontrol.StrikePreview;
import com.bridgecore.menu.FireControlMenu;
import com.bridgecore.menu.FireControlMenuActions;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import com.bridgecore.firecontrol.WeaponGroups;
import com.bridgecore.firecontrol.MacroReadout;
import com.bridgecore.network.FireControlGroupNamePayload;
import com.bridgecore.network.FireControlTurretNamePayload;
import com.bridgecore.network.FireControlIdentityPayload;
import com.bridgecore.network.FireControlSectorPayload;
import com.bridgecore.network.FireControlStrikeRequest;
import com.bridgecore.network.FireControlStrikeResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Core management: select a turret before changing it. Channel-wide controls
 *  have their own page; global maintenance is deliberately separate. Existing
 *  server commands remain authoritative. No inventory slots or textures. */
public class FireControlScreen extends AbstractContainerScreen<FireControlMenu> {
    private static final int BG = 0xFF101925, PANEL = 0xFF182637, LINE = 0xFF314457;
    private static final int TEXT = 0xFFE3EDF5, DIM = 0xFF9CB0C1, CYAN = 0xFF62D9EB;
    private static final int GREEN = 0xFF81D69C, AMBER = 0xFFFFCE78, RED = 0xFFFF8D8D;
    private static final int BODY_Y = 55, DETAIL_X = 120, LIST_ROW = 30;
    private static final String[] PAGES = {"武器管理", "武器编组", "核心设置", "坐标打击"};
    private static final String[] DETAILS = {"概览", "射界", "轴承", "炮座", "维护"};
    private static final int ARC_DETAIL = 1;
    private static final int STRIKE_PAGE = 3, STRIKE_ROWS_Y = 151;
    private static final String[] COORDINATE_AXES = {"X", "Y", "Z"};

    private final List<Button> buttons = new ArrayList<>();
    private int page, detail, selected = -1, listOffset, memberPage;
    private int selectedIdentity = -1, lastRosterEpoch = -1;
    private String signature = "";
    private EditBox groupNameEdit;
    private EditBox turretNameEdit;
    private UUID editingTurret;
    private String loadedTurretName = "";
    private int editingGroup = -1, groupListPage;
    private boolean groupPicker;
    private String loadedGroupName = "";
    private int pendingAction = -1;
    private String pendingTitle = "", pendingText = "", feedback = "";
    private int dragParam = -1, dragValue, activeBound, dragEpoch;
    private static final String[] SECTOR_FIELDS = {"偏航起点", "偏航终点", "俯仰下限", "俯仰上限"};
    private final EditBox[] sectorFields = new EditBox[SECTOR_FIELDS.length];
    private boolean sectorPitchView;
    private int sectorPitchDrag = -1;
    private int selectedSector, sectorDragYaw, sectorSelectionYaw = Integer.MIN_VALUE;
    private FireControlIdentityPayload.Turret sectorDragTurret;
    private UUID sectorEditorTurret;
    private SectorArc.Endpoint sectorEditorValue;
    private int sectorEditorEnd;
    private int sectorEditorYawSign;
    private final EditBox[] strikeCoordinates = new EditBox[3];
    private final String[] strikeCoordinateText = {"", "", ""};
    private int strikeChannel = -1, strikeOffset;
    private boolean strikeAllowTurn = true;
    private int strikeAxialChannels;
    private List<StrikePreview.Row> strikeRows = List.of();
    private UUID strikeRequest, strikeConfirmation;
    private String strikeFeedback = "";

    public FireControlScreen(FireControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 360;
        imageHeight = 240;
    }

    @Override
    protected void init() {
        imageWidth = Math.min(360, width - 8);
        imageHeight = Math.min(240, height - 8);
        super.init();
        groupNameEdit = null;
        turretNameEdit = null;
        for (int i = 0; i < sectorFields.length; i++) sectorFields[i] = null;
        sectorEditorTurret = null;
        for (int axis = 0; axis < strikeCoordinates.length; axis++) strikeCoordinates[axis] = null;
        editingGroup = -1;
        signature = "";
        dragParam = -1;
        sectorDragTurret = null;
        refresh();
    }

    public static boolean acceptStrikeResult(FireControlStrikeResult result) {
        if (!(Minecraft.getInstance().screen instanceof FireControlScreen screen)
                || screen.menu.containerId != result.menuId() || screen.page != STRIKE_PAGE) return false;
        if (result.request() != null) {
            if (!result.request().equals(screen.strikeRequest)) return false;
            screen.strikeRequest = null;
            screen.strikeConfirmation = result.executable() ? result.request() : null;
            if (!result.rows().isEmpty()) screen.strikeRows = result.rows();
        }
        screen.strikeFeedback = result.message();
        screen.signature = "";
        screen.refresh();
        return true;
    }

    private int contentWidth() { return imageWidth - DETAIL_X - 12; }
    private int visibleRows() { return Math.max(1, (imageHeight - BODY_Y - 60) / LIST_ROW); }
    private int memberRows() { return Math.max(1, (imageHeight - 190) / 20); }
    private boolean hasSelection() { return selected >= 0 && selected < menu.turretCount(); }
    private String turretName() { return hasSelection() ? menu.turretName(selected) : "未选择炮塔"; }

    private void refresh() {
        int count = menu.turretCount();
        if (memberCount() == 0) selected = -1;
        else if (selected < 0 || selected >= memberCount()) selected = 0;
        int identity = hasSelection() ? menu.identity(selected)
                : selected < 0 ? -1 : 0x10000 | menu.fixedGunIdentity(selected - count);
        if (identity != selectedIdentity || menu.rosterEpoch() != lastRosterEpoch) {
            selectedIdentity = identity;
            lastRosterEpoch = menu.rosterEpoch();
            pendingAction = -1;
            dragParam = -1;
            selectedSector = 0;
            sectorPitchView = false;
            sectorSelectionYaw = Integer.MIN_VALUE;
            feedback = "";
        }
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, memberCount() - visibleRows())));
        memberPage = Math.min(memberPage, Math.max(0, (memberCount() - 1) / memberRows()));
        if (page == STRIKE_PAGE) {
            int axial = 0;
            int turrets = 0;
            for (int r = 0; r < menu.fixedGunCount(); r++) axial |= menu.membership(r, true);
            for (int r = 0; r < count; r++) turrets |= menu.membership(r, false);
            int channels = WeaponGroups.axialOnly(axial, turrets);
            if (strikeChannel < 0 || strikeChannel >= menu.channelCount() || channels != strikeAxialChannels) {
                if (strikeChannel < 0 || strikeChannel >= menu.channelCount()) strikeChannel = menu.activeChannel();
                strikeAxialChannels = channels;
                strikeRequest = null;
                clearStrikePreview();
            }
        }
        StringBuilder state = new StringBuilder().append(page).append(':').append(detail)
                .append(':').append(selected).append(':').append(listOffset).append(':').append(memberPage)
                .append(':').append(groupPicker).append(':').append(groupListPage).append(':').append(menu.channelCount()).append(':').append(menu.identityVersion()).append(':').append(pendingAction).append(':').append(menu.activeChannel())
                .append(':').append(menu.skyFilterOn()).append(':').append(menu.followOn()).append(':').append(menu.pairingActive());
        state.append(':').append(selectedSector);
        for (int r = 0; r < count; r++) state.append('|').append(menu.identity(r)).append(',')
                .append(menu.status(r)).append(',').append(menu.membership(r, false)).append(',')
                .append(menu.fireInterval(r)).append(',').append(menu.cadenceAdjustable(r))
                .append(',').append(menu.mountCount(r)).append(',').append(menu.paired(r))
                .append(',').append(menu.arcYawMin(r)).append(',').append(menu.arcYawMax(r))
                .append(',').append(menu.arcPitchMin(r)).append(',').append(menu.arcPitchMax(r));
        for (int ch = 0; ch < menu.channelCount(); ch++) state.append('/').append(menu.channelAuto(ch))
                .append(menu.channelPromote(ch)).append(menu.promoteSuppressed(ch));
        state.append('#').append(menu.fixedGunCount());
        for (int r = 0; r < menu.fixedGunCount(); r++) state.append('|').append(menu.fixedGunIdentity(r))
                .append(',').append(menu.membership(r, true));
        String next = state.toString();
        if (!next.equals(signature)) {
            signature = next;
            rebuild();
        }
        updateGroupEditor();
        updateTurretEditor();
        updateStrikeEditor();
        updateSectorEditor();
    }

    private void rebuild() {
        for (Button button : buttons) removeWidget(button);
        buttons.clear();
        int tabWidth = Math.min(82, (imageWidth - 20) / PAGES.length);
        for (int p = 0; p < PAGES.length; p++) {
            final int target = p;
            button(12 + p * tabWidth, 27, tabWidth - 4, 18, PAGES[p], page == p, true, () -> {
                if (page == target) return;
                if (page == STRIKE_PAGE) {
                    strikeRequest = null;
                    clearStrikePreview();
                }
                page = target; groupPicker = false; sectorDragTurret = null; dragParam = -1; feedback = ""; signature = "";
            }, "");
        }
        button(imageWidth - 64, imageHeight - 21, 52, 16, "关闭", false, true, this::onClose, "Esc 也可关闭");
        if (page == 0) buildTurrets();
        else if (page == 1) buildChannels();
        else if (page == STRIKE_PAGE) buildStrike();
        else buildSettings();
        if (pendingAction >= 0) {
            for (Button button : buttons) button.active = false;
            int x = 18, y = modalY();
            button(x + 12, y + modalHeight() - 26, 104, 18, "确认操作", false, true, () -> {
                int action = pendingAction;
                pendingAction = -1;
                send(action);
                feedback = "操作已发送，状态以服务端回传为准";
                signature = "";
            }, "");
            button(imageWidth - x - 92, y + modalHeight() - 26, 80, 18, "取消", false, true, () -> {
                pendingAction = -1; signature = "";
            }, "Esc 取消本次操作");
        }
    }

    private void buildTurrets() {
        int count = memberCount();
        for (int i = listOffset; i < Math.min(count, listOffset + visibleRows()); i++) {
            final int row = i;
            button(10, BODY_Y + 17 + (i - listOffset) * LIST_ROW, 98, 28, "", i == selected, true, () -> {
                selected = row; pendingAction = -1; sectorDragTurret = null; dragParam = -1; feedback = ""; signature = "";
            }, weaponName(i) + " · " + weaponType(i));
        }
        if (count > visibleRows()) {
            button(10, imageHeight - 43, 46, 16, "上一组", false, listOffset > 0, () -> {
                listOffset = Math.max(0, listOffset - visibleRows()); signature = "";
            }, "鼠标滚轮也可浏览列表");
            button(60, imageHeight - 43, 48, 16, "下一组", false,
                    listOffset + visibleRows() < count, () -> {
                        listOffset = Math.min(Math.max(0, memberCount() - visibleRows()), listOffset + visibleRows()); signature = "";
                    }, "");
        }
        if (!hasSelection()) return;
        int cw = contentWidth(), tabW = (cw - 2 * (DETAILS.length - 1)) / DETAILS.length;
        for (int d = 0; d < DETAILS.length; d++) {
            final int target = d;
            button(DETAIL_X + d * (tabW + 2), 87, tabW, 17, DETAILS[d], detail == d, true, () -> {
                detail = target; sectorDragTurret = null; dragParam = -1; feedback = ""; signature = "";
            }, "");
        }
        if (detail == 0) {
            var info = menu.turretInfo(selected);
            button(DETAIL_X + cw - 95, 108, 44, 16, "改名", false, info != null,
                    this::saveTurretName, "名称会显示在炮塔列表和打击试算中");
            button(DETAIL_X + cw - 47, 108, 47, 16, "定位", false, info != null && (info.yawBlock() != null || !info.mounts().isEmpty()),
                    () -> locateTurret(info), "关闭面板并高亮已加载炮座 12 秒");
        }
        if (detail == 0 && menu.cadenceAdjustable(selected)) {
            int y = imageHeight - 48;
            button(DETAIL_X, y, 38, 17, "降速", false, true,
                    () -> send(FireControlMenuActions.CADENCE_DOWN_BASE + selected), "只调整本塔的射速上限");
            button(DETAIL_X + cw - 38, y, 38, 17, "升速", false, true,
                    () -> send(FireControlMenuActions.CADENCE_UP_BASE + selected), "炮闩装填节奏仍独立生效");
        } else if (detail == ARC_DETAIL && sectorMode()) {
            buildSectors();
        } else if (detail == ARC_DETAIL) {
            var turret = arcTurret();
            button(DETAIL_X + cw - 82, 106, 82, 16, "切换为分区", false, turret != null, () ->
                    send(FireControlMenuActions.SECTOR_RESUME_BASE + selected),
                    "恢复已保存的分区，首次启用时沿用普通俯仰范围");
            for (int p = 0; p < 4; p++) {
                final int param = p;
                int half = cw / 2, x = DETAIL_X + (p / 2) * half;
                int column = p < 2 && yawRightSign() < 0 ? 1 - p : p % 2;
                int bw = (half - 6) / 2;
                button(x + column * (bw + 2), 123, bw, 16, boundText(p), activeBound == p, true, () -> {
                    activeBound = param; signature = "";
                }, "先选择边界，再拖动黄点；青点表示当前朝向");
            }
        } else if (detail == 3) {
            for (int slot = 0; slot < 4; slot++) {
                final int target = slot;
                int packed = menu.mountPacked(selected, slot);
                if (packed == FireControlMenu.MOUNT_SLOT_EMPTY) continue;
                int no = packed >> 4;
                button(DETAIL_X + cw - 42, mountY(slot) + 3, 42, 17, "解绑", false, true, () -> confirm(
                        FireControlMenu.MOUNT_UNBIND_BASE + selected * 16 + target,
                        "解绑炮座", "只解绑" + turretName() + "的炮座 #" + no + "。保留本塔轴承配对和其他炮座。"), mountTip(selected, target));
            }
        } else if (detail == 4) {
            button(DETAIL_X, 111, Math.min(150, cw), 19, "重新校准本塔", false,
                    menu.paired(selected) && menu.status(selected) != FireControlMenu.STATUS_CALIBRATING, () -> {
                        send(FireControlMenuActions.RECALIBRATE_BASE + selected);
                        feedback = "仅重校" + turretName() + "，其余炮塔保持工作";
                    }, "本塔会摆动并暂时退出分配；其他炮塔不重校");
            button(DETAIL_X, 174, Math.min(150, cw), 19, "解除本塔配对…", false, true, () -> confirm(
                    selected, "解除炮塔配对", "解除" + turretName() + "的配对与全部炮座链接。其他炮塔不受此操作影响。"), "重新配对会自动校准新配对的炮塔");
        }
    }

    private void buildChannels() {
        int ch = menu.activeChannel();
        int count = menu.channelCount();
        if (groupPicker) {
            buildGroupPicker();
            return;
        }
        button(12, BODY_Y, 24, 19, "‹", false, count > 1,
                () -> send(FireControlMenuActions.GROUP_SELECT + (ch + count - 1) % count), "上一组");
        button(40, BODY_Y, 24, 19, "›", false, count > 1,
                () -> send(FireControlMenuActions.GROUP_SELECT + (ch + 1) % count), "下一组");
        button(70, BODY_Y, imageWidth - 148, 19, (ch + 1) + "/" + count + " · " + menu.channelName(ch),
                true, true, () -> {
                    groupPicker = true;
                    groupListPage = ch / groupsPerPage();
                    signature = "";
                }, "查看所有编组");
        button(imageWidth - 72, BODY_Y, 60, 19, "+ 新编组", false, count < WeaponGroups.MAX,
                () -> send(FireControlMenuActions.GROUP_ADD), "新增并选择编组，最多 " + WeaponGroups.MAX + " 组");
        button(imageWidth - 72, 78, 60, 17, "保存组名", false, true, this::saveGroupName, "最多 24 字符");
        int half = (imageWidth - 28) / 2;
        button(12, 110, half, 18, "自动升格：" + (!menu.channelPromote(ch) ? "关" : menu.promoteSuppressed(ch) ? "已暂停" : "开"),
                menu.channelPromote(ch) && !menu.promoteSuppressed(ch), true,
                () -> send(FireControlMenuActions.GROUP_PROMOTE + ch), "作用于整条频道；将 HUD 预锁升为多目标任务");
        button(16 + half, 110, half, 18, "自动开火：" + (menu.channelAuto(ch) ? "开" : "关"), menu.channelAuto(ch), true,
                () -> send(FireControlMenuActions.GROUP_AUTO + ch), "作用于整条频道；开关本身不创建目标任务");
        button(12, 133, half, 18, "视线跟随：" + (menu.followOn() ? "开" : "关"), menu.followOn(), true,
                () -> send(FireControlMenu.BUTTON_TOGGLE_FOLLOW), "当前频道跟随操作者视线");
        button(16 + half, 133, half, 18, "当前频道齐射", false, membersIn(ch) + gunsIn(ch) > 0,
                () -> send(FireControlMenu.BUTTON_FIRE_VOLLEY), "只向当前频道发出一次齐射请求，仍受开火闸门限制");
        boolean hasTurrets = membersIn(ch) > 0;
        boolean hasAxisGuns = gunsIn(ch) > 0;
        int start = memberPage * memberRows();
        for (int r = start; r < Math.min(memberCount(), start + memberRows()); r++) {
            final int row = r;
            int y = 174 + (r - start) * 20;
            boolean gun = r >= menu.turretCount();
            int gunRow = r - menu.turretCount();
            boolean included = menu.belongsTo(gun ? gunRow : r, gun, ch);
            boolean canJoin = gun ? !hasTurrets : !hasAxisGuns;
            button(imageWidth - 92, y - 3, 80, 16, included ? "移出当前频道" : "编入当前频道", included, included || canJoin,
                    () -> send(FireControlMenuActions.groupMembership(gun ? gunRow : row, gun, ch, !included)),
                    included || canJoin ? "只修改本组归属" : "轴炮和转塔使用各自的频道");
        }
        int pages = Math.max(1, (memberCount() + memberRows() - 1) / memberRows());
        if (pages > 1) {
            button(12, imageHeight - 21, 52, 16, "上一页", false, memberPage > 0,
                    () -> { memberPage--; signature = ""; }, "");
            button(68, imageHeight - 21, 52, 16, "下一页", false, memberPage + 1 < pages,
                    () -> { memberPage++; signature = ""; }, "");
        }
    }

    private void buildSettings() {
        button(12, 79, Math.min(190, imageWidth - 24), 20,
                "露天过滤：" + (menu.skyFilterOn() ? "开" : "关"), menu.skyFilterOn(), true,
                () -> send(FireControlMenu.BUTTON_TOGGLE_SKY_FILTER), "全核心扫描设置；开启时过滤遮蔽目标");
        button(12, 130, Math.min(190, imageWidth - 24), 19, "取消我的配对流程", false, menu.pairingActive(),
                () -> send(FireControlMenu.BUTTON_CANCEL_PAIR), "只取消当前玩家尚未完成的配对，不解除已配对炮塔");
        button(12, imageHeight - 64, Math.min(190, imageWidth - 24), 19, "全部炮塔重新校准…", false,
                menu.turretCount() > 0, () -> confirm(FireControlMenu.BUTTON_RECALIBRATE_ALL,
                        "全核心维护", "将重新校准全部 " + menu.turretCount() + " 座炮塔。全体暂时退出战斗分配，频道任务按现有规则重新评估。"), "全局操作；单塔校准请到炮塔管理 → 维护");
    }

    private void buildStrike() {
        boolean ready = strikeRequest == null;
        int modeWidth = Math.min(112, (imageWidth - 28) / 3);
        button(12, BODY_Y, 22, 18, "‹", false, ready && menu.channelCount() > 1,
                () -> changeStrikeChannel(-1), "上一组");
        button(38, BODY_Y, 22, 18, "›", false, ready && menu.channelCount() > 1,
                () -> changeStrikeChannel(1), "下一组");
        if (strikeMode() != StrikePreview.Mode.AXIS_BEAM) {
            button(imageWidth - 12 - modeWidth, BODY_Y, modeWidth, 18, "允许转舰 · " + (strikeAllowTurn ? "开" : "关"),
                    strikeAllowTurn, ready, () -> {
                        strikeAllowTurn = !strikeAllowTurn;
                        clearStrikePreview();
                    }, "当前舰姿无可用炮塔时，搜索可用舰姿");
        }
        int actionWidth = (imageWidth - 32) / 3;
        button(12, 113, actionWidth, 18, "预瞄", false, ready, () -> sendStrike(FireControlStrikeRequest.Action.PREVIEW), "");
        button(16 + actionWidth, 113, actionWidth, 18, "执行打击", false, ready && strikeConfirmation != null,
                () -> sendStrike(FireControlStrikeRequest.Action.EXECUTE), "复核已确认的炮位后执行一轮");
        button(20 + 2 * actionWidth, 113, actionWidth, 18, "取消打击", false, ready,
                () -> sendStrike(FireControlStrikeRequest.Action.CANCEL), "取消本界面正在等待瞄准的打击");
    }

    private StrikePreview.Mode strikeMode() {
        if ((strikeAxialChannels & WeaponGroups.bit(strikeChannel)) != 0) return StrikePreview.Mode.AXIS_BEAM;
        return strikeAllowTurn ? StrikePreview.Mode.TURRET_POSE : StrikePreview.Mode.TURRET;
    }

    private void changeStrikeChannel(int step) {
        strikeChannel = Math.floorMod(strikeChannel + step, menu.channelCount());
        clearStrikePreview();
    }

    private void clearStrikePreview() {
        strikeConfirmation = null;
        strikeRows = List.of();
        strikeOffset = 0;
        strikeFeedback = "";
        signature = "";
    }

    private void updateStrikeEditor() {
        int stride = (imageWidth - 24) / 3;
        for (int axis = 0; axis < strikeCoordinates.length; axis++) {
            var box = strikeCoordinates[axis];
            if (page != STRIKE_PAGE) {
                if (box != null) removeWidget(box);
                strikeCoordinates[axis] = null;
            } else {
                if (box == null) {
                    int index = axis;
                    box = new EditBox(font, leftPos + 25 + axis * stride, topPos + 89, stride - 16, 18,
                            Component.literal(COORDINATE_AXES[axis]));
                    box.setMaxLength(11);
                    box.setFilter(value -> value.matches("-?\\d{0,10}"));
                    box.setValue(strikeCoordinateText[index]);
                    box.setResponder(value -> {
                        strikeCoordinateText[index] = value;
                        clearStrikePreview();
                    });
                    strikeCoordinates[axis] = addRenderableWidget(box);
                }
                box.setEditable(strikeRequest == null);
            }
        }
    }

    private void sendStrike(FireControlStrikeRequest.Action action) {
        if (strikeRequest != null) return;
        BlockPos target = BlockPos.ZERO;
        if (action != FireControlStrikeRequest.Action.CANCEL) {
            try {
                target = new BlockPos(Integer.parseInt(strikeCoordinateText[0]), Integer.parseInt(strikeCoordinateText[1]),
                        Integer.parseInt(strikeCoordinateText[2]));
            } catch (NumberFormatException invalid) {
                strikeFeedback = "请输入完整的 X、Y、Z 方块坐标";
                return;
            }
        }
        UUID request = action == FireControlStrikeRequest.Action.EXECUTE ? strikeConfirmation : UUID.randomUUID();
        if (request == null) return;
        if (action == FireControlStrikeRequest.Action.PREVIEW) clearStrikePreview();
        strikeRequest = request;
        strikeConfirmation = null;
        strikeFeedback = switch (action) {
            case PREVIEW -> "正在预瞄";
            case EXECUTE -> "正在提交打击";
            case CANCEL -> "正在取消打击";
        };
        PacketDistributor.sendToServer(new FireControlStrikeRequest(menu.containerId, request, action, target,
                strikeChannel, strikeMode().wireId));
        signature = "";
        refresh();
    }

    private int strikeVisibleRows() {
        return Math.max(0, (imageHeight - 26 - STRIKE_ROWS_Y) / 14);
    }

    private void drawStrike(GuiGraphics g) {
        text(g, fit(menu.channelName(strikeChannel), imageWidth - Math.min(112, (imageWidth - 28) / 3) - 86), 67, 60, CYAN);
        text(g, (strikeMode() == StrikePreview.Mode.AXIS_BEAM ? "轴炮" : "转塔炮击") + " · 世界方块坐标", 12, 77, DIM);
        int stride = (imageWidth - 24) / 3;
        for (int axis = 0; axis < COORDINATE_AXES.length; axis++) text(g, COORDINATE_AXES[axis], 12 + axis * stride, 94, DIM);
        text(g, fit(strikeFeedback, imageWidth - 24), 12, 136, AMBER);
        int split = imageWidth * 3 / 5;
        for (int i = strikeOffset; i < Math.min(strikeOffset + strikeVisibleRows(), strikeRows.size()); i++) {
            var row = strikeRows.get(i);
            int y = STRIKE_ROWS_Y + (i - strikeOffset) * 14;
            String weapon = row.weapon().isEmpty() ? row.name() : row.name() + " · " + row.weapon();
            text(g, fit(weapon, split - 20), 12, y, TEXT);
            text(g, fit(row.reason(), imageWidth - split - 12), split, y, row.eligible() ? GREEN : DIM);
        }
    }

    private int membersIn(int ch) {
        int count = 0;
        for (int row = 0; row < menu.turretCount(); row++) if (menu.belongsTo(row, false, ch)) count++;
        return count;
    }

    private int gunsIn(int ch) {
        int count = 0;
        for (int row = 0; row < menu.fixedGunCount(); row++) if (menu.belongsTo(row, true, ch)) count++;
        return count;
    }

    private int memberCount() { return menu.turretCount() + menu.fixedGunCount(); }

    private String weaponName(int row) {
        return row < menu.turretCount() ? menu.turretName(row) : menu.fixedGunName(row - menu.turretCount());
    }

    private String weaponType(int row) {
        return row < menu.turretCount() ? menu.weaponLabel(row) : "船体固定宏炮";
    }

    private int groupsPerPage() { return Math.max(2, ((imageHeight - 113) / 20) * 2); }

    private void buildGroupPicker() {
        int count = menu.channelCount(), perPage = groupsPerPage();
        int width = (imageWidth - 28) / 2;
        for (int i = 0; i < perPage; i++) {
            int group = groupListPage * perPage + i;
            if (group >= count) break;
            button(12 + (i % 2) * (width + 4), 73 + (i / 2) * 20, width, 18,
                    (group + 1) + " · " + menu.channelName(group), group == menu.activeChannel(), true, () -> {
                        send(FireControlMenuActions.GROUP_SELECT + group);
                        groupPicker = false;
                        signature = "";
                    }, menu.channelName(group));
        }
        int bottom = imageHeight - 45;
        button(12, bottom, 50, 16, "上一页", false, groupListPage > 0,
                () -> { groupListPage--; signature = ""; }, "");
        button(66, bottom, 50, 16, "下一页", false, (groupListPage + 1) * perPage < count,
                () -> { groupListPage++; signature = ""; }, "");
        button(imageWidth - 72, bottom, 60, 16, "返回", false, true,
                () -> { groupPicker = false; signature = ""; }, "");
    }

    private void updateTurretEditor() {
        var info = page == 0 && detail == 0 && hasSelection() && pendingAction < 0
                ? menu.turretInfo(selected) : null;
        if (info == null) {
            if (turretNameEdit != null) removeWidget(turretNameEdit);
            turretNameEdit = null;
            editingTurret = null;
            return;
        }
        if (turretNameEdit == null) {
            turretNameEdit = new EditBox(font, leftPos + DETAIL_X, topPos + 108, contentWidth() - 100, 16,
                    Component.literal("炮塔名称"));
            turretNameEdit.setMaxLength(WeaponGroups.NAME_LIMIT);
            addRenderableWidget(turretNameEdit);
            editingTurret = null;
        }
        if (!info.id().equals(editingTurret) || !loadedTurretName.equals(info.name())) {
            editingTurret = info.id();
            loadedTurretName = info.name();
            turretNameEdit.setValue(loadedTurretName);
        }
    }

    private void saveTurretName() {
        if (turretNameEdit == null || editingTurret == null) return;
        String name = WeaponGroups.cleanName(turretNameEdit.getValue());
        if (name.isEmpty()) {
            feedback = "请输入有效的炮塔名称";
            return;
        }
        feedback = "";
        PacketDistributor.sendToServer(new FireControlTurretNamePayload(menu.containerId, editingTurret, name));
        setFocused(null);
    }

    private void locateTurret(FireControlIdentityPayload.Turret info) {
        if (minecraft == null || minecraft.level == null) return;
        var level = minecraft.level;
        var mounts = info.mounts().stream().map(FireControlIdentityPayload.Mount::block)
                .filter(java.util.Objects::nonNull).filter(level::hasChunkAt).distinct().toList();
        String label = "炮座 ×" + mounts.size();
        if (mounts.isEmpty()) {
            if (!info.mounts().isEmpty()) {
                feedback = "当前没有可定位的已加载炮座";
                return;
            }
            if (info.yawBlock() == null || !level.hasChunkAt(info.yawBlock())) {
                feedback = "偏航轴承未加载 · " + info.yaw();
                return;
            }
            mounts = List.of(info.yawBlock());
            label = "未配炮座 · 偏航轴承 " + info.yaw();
        }
        TurretLocator.show(level, mounts);
        if (minecraft.player != null) minecraft.player.displayClientMessage(
                Component.literal(info.name() + " · " + label), true);
        onClose();
    }

    private void updateGroupEditor() {
        if (page != 1 || groupPicker) {
            if (groupNameEdit != null) removeWidget(groupNameEdit);
            groupNameEdit = null;
            editingGroup = -1;
            return;
        }
        int group = menu.activeChannel();
        String name = menu.channelName(group);
        if (groupNameEdit == null) {
            groupNameEdit = new EditBox(font, leftPos + 12, topPos + 78, imageWidth - 88, 17,
                    Component.literal("编组名称"));
            groupNameEdit.setMaxLength(WeaponGroups.NAME_LIMIT);
            addRenderableWidget(groupNameEdit);
        }
        if (editingGroup != group || !loadedGroupName.equals(name)) {
            groupNameEdit.setValue(name);
            editingGroup = group;
            loadedGroupName = name;
        }
    }

    private void saveGroupName() {
        if (groupNameEdit == null || editingGroup != menu.activeChannel()) return;
        String name = WeaponGroups.cleanName(groupNameEdit.getValue());
        if (name.isEmpty()) return;
        PacketDistributor.sendToServer(new FireControlGroupNamePayload(menu.containerId, editingGroup, name));
        groupNameEdit.setFocused(false);
        setFocused(null);
    }

    private String groupSummary(int row, boolean macro) {
        int mask = menu.membership(row, macro);
        return mask == 0 ? "未编组" : Integer.bitCount(mask) + " 组 · 指挥 " + menu.channelName(
                macro ? menu.fixedGunChannel(row) : menu.turretChannel(row));
    }

    private void drawMacro(GuiGraphics g, int row) {
        var aim = menu.fixedGunAim(row);
        int x = DETAIL_X, width = contentWidth();
        var info = menu.fixedGunInfo(row);
        text(g, fit(menu.fixedGunName(row) + " · 宏炮", width), x, 59, TEXT);
        text(g, fit(groupSummary(row, true), width), x, 74, DIM);
        text(g, fit("开火条件  " + menu.fixedGunReason(row), width), x, 95, aim.ready() ? GREEN : AMBER);
        text(g, fit("射程 " + meters(aim.range()) + " · 距离 " + meters(aim.distance()), width), x, 112, TEXT);
        text(g, fit("射束偏角  " + (aim.degrees() < 0 ? "—" : String.format(Locale.ROOT, "%.1f°", aim.degrees())), width), x, 128, TEXT);
        boolean known = aim.gate() != MacroReadout.Gate.UNKNOWN && menu.fixedGunStatus(row) >= 0;
        text(g, fit(known ? "储能  " + compactFe(menu.fixedGunEnergy(row)) + " / " + compactFe(menu.fixedGunEnergyCap(row)) + " FE" : "储能  —", width), x, 145, TEXT);
        text(g, fit(known ? "热量 " + menu.fixedGunHeat(row) + "/" + menu.fixedGunMaxHeat(row)
                + " · 单发 " + compactFe(menu.fixedGunShotCost(row)) + " FE" : "热量  —", width), x, 161, TEXT);
        text(g, fit(known ? "预热/冷却 " + menu.fixedGunWarmup(row) + "t / " + menu.fixedGunCooldown(row) + "t"
                : "预热 / 冷却  —", width), x, 177, DIM);
        if (info != null) text(g, fit("炮位  " + info.location(), width), x, 193, DIM);
    }

    private static String meters(double value) {
        return value < 0 ? "—" : (int) value + "m";
    }

    private static String compactFe(long value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    private void button(int x, int y, int w, int h, String label, boolean chosen, boolean enabled, Runnable action, String tip) {
        Button button = new FlatButton(leftPos + x, topPos + y, w, h, Component.literal(label), b -> action.run(), chosen);
        button.active = enabled;
        if (!tip.isEmpty()) button.setTooltip(Tooltip.create(Component.literal(tip)));
        buttons.add(button);
        addRenderableWidget(button);
    }

    private void confirm(int action, String title, String text) {
        pendingAction = action; pendingTitle = title; pendingText = text; signature = "";
        refresh();
    }

    private void send(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, FireControlMenuActions.guarded(id, menu.rosterEpoch()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.render(g, mouseX, mouseY, partialTick);
        if (pendingAction < 0) {
            renderTooltip(g, mouseX, mouseY);
            int x = mouseX - leftPos, y = mouseY - topPos;
            String tip = "";
            if (page == STRIKE_PAGE && x >= 12 && x < imageWidth - 12) {
                if (y >= 135 && y < 147) tip = strikeFeedback;
                int offset = (y - STRIKE_ROWS_Y) / 14;
                int row = strikeOffset + offset;
                if (y >= STRIKE_ROWS_Y && offset < strikeVisibleRows() && row < strikeRows.size()) {
                    var item = strikeRows.get(row);
                    tip = item.name() + " · " + item.reason();
                }
            }
            if (page == 0 && hasSelection() && x >= DETAIL_X && x < imageWidth - 12) {
                if (y >= BODY_Y && y < 83) tip = turretName() + " · " + menu.weaponLabel(selected);
                var info = menu.turretInfo(selected);
                if (detail == 2 && info != null && y >= 109 && y < imageHeight - 26)
                    tip = "偏航：" + info.yaw() + " · 俯仰：" + info.pitch() + " · 核心格偏移：" + info.coreOffset();
                if (detail == 3 && x < imageWidth - 60) {
                    for (int slot = 0; slot < 4; slot++) if (menu.mountPacked(selected, slot) != FireControlMenu.MOUNT_SLOT_EMPTY
                            && y >= mountY(slot) && y < mountY(slot) + 22) tip = mountTip(selected, slot);
                }
            }
            if (!tip.isEmpty()) g.renderTooltip(font, font.split(Component.literal(tip), Math.min(280, width - 20)), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, LINE);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 23, PANEL);
        g.fill(leftPos + 8, topPos + 49, leftPos + imageWidth - 8, topPos + 50, LINE);
        g.fill(leftPos + 8, topPos + imageHeight - 26, leftPos + imageWidth - 8, topPos + imageHeight - 25, LINE);
        if (page == 0) g.fill(leftPos + 113, topPos + BODY_Y, leftPos + 114, topPos + imageHeight - 30, LINE);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        text(g, "火控核心", 12, 9, TEXT);
        right(g, fit(menu.turretCount() + " 座炮塔"
                + (menu.fixedGunCount() > 0 ? " · " + menu.fixedGunCount() + " 轴炮" : "")
                + " · " + menu.channelName(menu.activeChannel()), imageWidth - 120), imageWidth - 12, 9, DIM);
        if (page == 0) drawTurrets(g);
        else if (page == 1) drawChannels(g);
        else if (page == STRIKE_PAGE) drawStrike(g);
        else drawSettings(g);
        if (page == STRIKE_PAGE) {
            if (strikeVisibleRows() > 0 && strikeRows.size() > strikeVisibleRows()) {
                text(g, "滚轮查看全部 " + strikeRows.size() + " 个炮位", 12, imageHeight - 17, DIM);
            }
        } else if (groupPicker || page != 1 || memberCount() <= memberRows()) {
            String hint = !feedback.isEmpty() ? feedback : page == 0
                    ? detail == ARC_DETAIL ? (sectorMode() ? "拖动端点或输入角度 · 青点为当前偏航"
                    : "拖动黄点改边界 · 青点为当前角度") : "炮塔操作只作用于选中对象"
                    : page == 1 ? "当前编组优先指挥共有武器" : "扫描设置与全局维护";
            text(g, fit(hint, imageWidth - 92), 12, imageHeight - 17, DIM);
        } else text(g, (memberPage + 1) + "/" + Math.max(1, (memberCount() + memberRows() - 1) / memberRows()), 128, imageHeight - 17, DIM);
        if (pendingAction >= 0) {
            // Modal is drawn over labels; its two widgets render on the same
            // foreground plane, with the underlying controls disabled.
            g.fill(1, 24, imageWidth - 1, imageHeight - 1, 0xDF101925);
            int x = 18, y = modalY();
            g.fill(x, y, imageWidth - x, y + modalHeight(), PANEL);
            g.renderOutline(x, y, imageWidth - x * 2, modalHeight(), AMBER);
            text(g, pendingTitle, x + 12, y + 10, AMBER);
            int ty = y + 27;
            for (var line : font.split(Component.literal(pendingText), imageWidth - 2 * x - 24)) {
                g.drawString(font, line, x + 12, ty, TEXT, false); ty += 11;
            }
            // Container labels render after widgets, so redraw only the modal
            // buttons here to keep them above its opaque surface.
            g.pose().pushPose();
            g.pose().translate(-leftPos, -topPos, 0);
            for (int i = Math.max(0, buttons.size() - 2); i < buttons.size(); i++) buttons.get(i).render(g, mouseX, mouseY, 0);
            g.pose().popPose();
        }
    }

    private void drawTurrets(GuiGraphics g) {
        text(g, "已登记武器", 12, BODY_Y + 3, DIM);
        for (int row = listOffset; row < Math.min(memberCount(), listOffset + visibleRows()); row++) {
            int y = BODY_Y + 20 + (row - listOffset) * LIST_ROW;
            text(g, fit(weaponName(row), 86), 16, y, row == selected ? CYAN : TEXT);
            text(g, fit(weaponType(row), 86), 16, y + 13, DIM);
            int color = row < menu.turretCount() ? statusColor(row)
                    : menu.fixedGunAim(row - menu.turretCount()).ready() ? GREEN : AMBER;
            g.fill(104, y + 2, 107, y + 6, color);
        }
        if (selected < 0) {
            text(g, "尚未登记武器", DETAIL_X, 76, TEXT);
            wrapped(g, "用配对工具登记炮塔或同船宏炮核心。", DETAIL_X, 99, contentWidth(), DIM);
            return;
        }
        if (!hasSelection()) {
            drawMacro(g, selected - menu.turretCount());
            return;
        }
        text(g, fit(turretName(), contentWidth()), DETAIL_X, BODY_Y + 3, TEXT);
        text(g, fit(menu.weaponLabel(selected), contentWidth()), DETAIL_X, BODY_Y + 16, DIM);
        switch (detail) {
            case ARC_DETAIL -> {
                if (sectorMode()) drawSectors(g);
                else drawArcs(g);
            }
            case 2 -> drawBearings(g);
            case 3 -> drawMounts(g);
            case 4 -> {
                wrapped(g, "仅本塔摆动并暂时退出分配；频道按剩余可用成员继续工作。", DETAIL_X, 135, contentWidth(), DIM);
            }
            default -> {
                var info = menu.turretInfo(selected);
                text(g, fit(info == null ? "偏航轴承信息同步中" : "偏航轴承 " + info.yaw(), contentWidth()), DETAIL_X, 129, DIM);
                text(g, fit(status(selected) + " · " + groupSummary(selected, false), contentWidth()), DETAIL_X, 146, statusColor(selected));
                int yaw = menu.relYaw10(selected);
                if (yaw != FireControlMenu.REL_UNKNOWN) yaw *= yawRightSign();
                text(g, fit("偏航 " + angle(yaw) + "   俯仰 " + angle(menu.relPitch10(selected)), contentWidth()),
                        DETAIL_X, 159, TEXT);
                int err = menu.aimErr10(selected);
                text(g, fit(FireControlUiText.gate(menu.gate(selected)) + " · " + (err < 0 ? "—" : String.format(Locale.ROOT, "%.1f°", err / 10.0))
                        + "   炮座 " + menu.mountCount(selected) + "/4", contentWidth()), DETAIL_X, 172, TEXT);

                if (!menu.cadenceAdjustable(selected)) {
                    text(g, fit("射速由武器装填机构控制", contentWidth()), DETAIL_X, imageHeight - 42, DIM);
                } else {
                    int interval = menu.fireInterval(selected);
                    String rate = interval > 0 ? 1200 / interval + " 发/分" : "—";
                    text(g, rate, DETAIL_X + (contentWidth() - font.width(rate)) / 2, imageHeight - 44, TEXT);
                }
            }
        }
    }

    private String status(int row) {
        if (!menu.paired(row)) return "未配对";
        return menu.status(row) == FireControlMenu.STATUS_READY ? "已校准" : "校准中";
    }
    private int statusColor(int row) { return menu.status(row) == FireControlMenu.STATUS_READY ? GREEN : AMBER; }

    private void drawChannels(GuiGraphics g) {
        int ch = menu.activeChannel();
        if (groupPicker) {
            text(g, "选择编组 · " + (groupListPage + 1) + "/"
                    + ((menu.channelCount() + groupsPerPage() - 1) / groupsPerPage()), 12, 58, CYAN);
            return;
        }
        text(g, fit("任务：" + FireControlUiText.source(menu.channelSource(ch)) + " · " + FireControlUiText.mode(menu.channelMode(ch))
                + "   成员 " + (membersIn(ch) + gunsIn(ch)), imageWidth - 24), 12, 98, TEXT);
        text(g, "武器成员 · 可加入多组", 12, 158, DIM);
        int start = memberPage * memberRows();
        for (int r = start; r < Math.min(memberCount(), start + memberRows()); r++) {
            int y = 174 + (r - start) * 20;
            if (r < menu.turretCount()) {
                text(g, fit(menu.turretName(r) + " · " + menu.weaponLabel(r) + " · " + groupSummary(r, false), imageWidth - 115), 12, y, TEXT);
            } else {
                int gunRow = r - menu.turretCount();
                text(g, fit(menu.fixedGunName(gunRow) + " · " + menu.fixedGunReason(gunRow), imageWidth - 115), 12, y, TEXT);
            }
        }
        if (memberCount() == 0) text(g, "先配对炮塔或登记轴炮，再编入频道", 12, 175, DIM);
    }

    private void drawSettings(GuiGraphics g) {
        text(g, "扫描规则 · 全核心", 12, 63, TEXT);
        wrapped(g, menu.skyFilterOn() ? "过滤受遮蔽的目标，避免隔墙锁定。" : "允许扫描受遮蔽目标；可能增加扫描开销。", 12, 105, imageWidth - 24, DIM);
        text(g, menu.pairingActive() ? "我的配对流程进行中" : "当前没有进行中的配对流程", 12, 154, DIM);
        text(g, "影响所有炮塔；单塔维护请到炮塔管理", 12, imageHeight - 39, AMBER);
    }

    private String angle(int value) {
        return value == FireControlMenu.REL_UNKNOWN ? "—" : String.format(Locale.ROOT, "%.1f°", value / 10.0);
    }

    private int modalHeight() {
        return Math.max(97, 52 + font.split(Component.literal(pendingText), imageWidth - 60).size() * 11);
    }
    private int modalY() { return (imageHeight - modalHeight()) / 2; }
    private int mountY(int slot) { return 110 + slot * ((imageHeight - 138) / 4); }

    private String mountTip(int row, int slot) {
        var mount = menu.mountInfo(row, slot);
        return mount == null ? "正在同步炮座信息" : menu.turretName(row) + " · 炮座 #" + mount.no()
                + " · " + mount.weapon() + "\n世界坐标 " + mount.location() + "\n仅解绑这一个炮座";
    }

    private void drawBearings(GuiGraphics g) {
        var info = menu.turretInfo(selected);
        text(g, "偏航轴承 · 世界坐标", DETAIL_X, 112, CYAN);
        text(g, fit(info == null ? "信息同步中" : info.yaw(), contentWidth()), DETAIL_X, 126, TEXT);
        text(g, "俯仰轴承 · 世界坐标", DETAIL_X, 148, CYAN);
        text(g, fit(info == null ? "信息同步中" : info.pitch(), contentWidth()), DETAIL_X, 162, TEXT);
        text(g, fit("偏航轴承相对核心的格坐标", contentWidth()), DETAIL_X, imageHeight - 57, DIM);
        text(g, fit(info == null ? "信息同步中" : info.coreOffset(), contentWidth()), DETAIL_X, imageHeight - 43, TEXT);
    }

    private void drawMounts(GuiGraphics g) {
        if (menu.mountCount(selected) == 0) {
            text(g, "尚未挂载炮座", DETAIL_X, 113, TEXT);
            wrapped(g, "使用配对工具右键炮座，将它挂到对应炮塔。", DETAIL_X, 137, contentWidth(), DIM);
        }
        for (int slot = 0; slot < 4; slot++) {
            int packed = menu.mountPacked(selected, slot);
            if (packed == FireControlMenu.MOUNT_SLOT_EMPTY) continue;
            var mount = menu.mountInfo(selected, slot);
            int code = (packed & 15) - 1, y = mountY(slot);
            text(g, fit("#" + (packed >> 4) + " " + (mount == null ? "信息同步中" : mount.weapon()), contentWidth() - 48), DETAIL_X, y, TEXT);
            text(g, fit(FireControlUiText.mount(code) + (mount == null ? "" : " · " + mount.location()), contentWidth() - 48), DETAIL_X, y + 11,
                    code == 1 ? GREEN : code == 5 ? RED : AMBER);
        }
    }

    private int dialRadius() { return Math.min(26, Math.max(18, (imageHeight - 180) / 2)); }
    private int dialY() { return imageHeight - 60; }
    private int dialX(boolean yaw) { return DETAIL_X + contentWidth() * (yaw ? 1 : 3) / 4; }

    private FireControlIdentityPayload.Turret arcTurret() {
        return page == 0 && detail == ARC_DETAIL && hasSelection() && menu.paired(selected)
                ? menu.turretInfo(selected) : null;
    }

    private boolean sectorMode() {
        var turret = arcTurret();
        return turret != null && turret.sectors().enabled();
    }

    private int sectorFieldX() { return DETAIL_X + 56; }

    private int sectorFieldWidth() { return (contentWidth() - 56 - 3 * (SECTOR_FIELDS.length - 1)) / SECTOR_FIELDS.length; }

    private int sectorFieldX(int field) { return sectorFieldX() + field * (sectorFieldWidth() + 3); }

    private int sectorDialY() { return 169 - sectorLayoutShift(); }

    private int sectorLayoutShift() { return Math.max(0, 232 - imageHeight); }

    private void buildSectors() {
        var turret = arcTurret();
        if (turret == null) return;
        var arc = turret.sectors();
        int width = (contentWidth() - 6) / 3;
        selectedSector = Math.clamp(selectedSector, 0, arc.endpoints().size() - 1);
        if (sectorSelectionYaw != Integer.MIN_VALUE) {
            for (int i = 0; i < arc.endpoints().size(); i++) {
                if (arc.endpoints().get(i).yaw() == sectorSelectionYaw) {
                    selectedSector = i;
                    sectorSelectionYaw = Integer.MIN_VALUE;
                    break;
                }
            }
        }
        button(DETAIL_X, 110, width, 16, "+ 端点", false, arc.canSplit(selectedSector),
                () -> sendSectorArc(turret, arc.split(selectedSector)), "在所选区中间添加端点，最多四点");
        button(DETAIL_X + width + 3, 110, width, 16, "删端点", false, arc.endpoints().size() > 2,
                () -> sendSectorArc(turret, arc.remove(selectedSector)), "删除所选区的起点，由前一区接管");
        button(DETAIL_X + 2 * (width + 3), 110, width, 16, "切换为普通", false, true,
                () -> sendSectorArc(turret, SectorArc.UNIFORM), "恢复普通射界，保留分区设置");
        int tabWidth = (contentWidth() - 62) / SectorArc.MAX_ENDPOINTS;
        for (int i = 0; i < arc.endpoints().size(); i++) {
            int index = i;
            boolean enabled = arc.endpoints().get(i).enabled();
            button(sectorFieldX() + i * (tabWidth + 2), 131, tabWidth, 16, (i + 1) + (enabled ? "区" : "区·停"),
                    selectedSector == i,
                    true, () -> selectSector(index), "");
        }
        var point = arc.endpoints().get(selectedSector);
        int actionWidth = (contentWidth() - 59) / 2;
        button(sectorFieldX(), 187 - sectorLayoutShift(), actionWidth, 16, "保存区间", false, true,
                this::saveSector, "终点与下一区起点共用");
        button(DETAIL_X, sectorDialY() + SECTOR_DIAL_RADIUS + 6, 49, 16,
                sectorPitchView ? "看偏航" : "看俯仰", false, true, () -> {
                    sectorPitchView = !sectorPitchView;
                    sectorDragTurret = null;
                    sectorPitchDrag = -1;
                    signature = "";
                }, "俯仰图可拖动上下限，再点保存区间");
        button(sectorFieldX() + actionWidth + 3, 187 - sectorLayoutShift(), actionWidth, 16, point.enabled() ? "禁用本区" : "启用本区",
                false, true, () -> sendSectorArc(turret, arc.replace(selectedSector,
                        new SectorArc.Endpoint(point.yaw(), point.lower(), point.upper(), !point.enabled()))),
                "禁用区不可瞄准或穿过，保留端点和俯仰设置");
    }

    private void updateSectorEditor() {
        var turret = arcTurret();
        if (sectorDragTurret != null && (turret == null || !sectorDragTurret.id().equals(turret.id())
                || !sectorDragTurret.sectors().equals(turret.sectors())
                || sectorDragTurret.yawRightSign() != turret.yawRightSign())) sectorDragTurret = null;
        if (turret == null || !turret.sectors().enabled()) {
            sectorPitchDrag = -1;
            for (int i = 0; i < sectorFields.length; i++) {
                if (sectorFields[i] != null) removeWidget(sectorFields[i]);
                sectorFields[i] = null;
            }
            sectorEditorTurret = null;
            sectorEditorValue = null;
            return;
        }
        var endpoint = turret.sectors().endpoints().get(selectedSector);
        int end = turret.sectors().endpoints().get((selectedSector + 1) % turret.sectors().endpoints().size()).yaw();
        if (turret.id().equals(sectorEditorTurret) && endpoint.equals(sectorEditorValue) && end == sectorEditorEnd
                && sectorEditorYawSign == turret.yawRightSign()) return;
        sectorEditorYawSign = turret.yawRightSign();
        sectorEditorTurret = turret.id();
        sectorEditorValue = endpoint;
        sectorEditorEnd = end;
        int[] values = {displayYaw(endpoint.yaw()), displayYaw(end), endpoint.lower(), endpoint.upper()};
        int width = sectorFieldWidth();
        for (int i = 0; i < sectorFields.length; i++) {
            if (sectorFields[i] == null) {
                var field = new EditBox(font, leftPos + sectorFieldX(i), topPos + 165 - sectorLayoutShift(),
                        width, 16, Component.literal(SECTOR_FIELDS[i]));
                field.setMaxLength(4);
                field.setTooltip(Tooltip.create(Component.literal(SECTOR_FIELDS[i] + (i == 1 ? " · 下一区起点" : ""))));
                field.setFilter(value -> value.matches("-?[0-9]{0,3}"));
                sectorFields[i] = addRenderableWidget(field);
            }
            sectorFields[i].setValue(Integer.toString(values[i]));
        }
    }

    private void selectSector(int index) {
        sectorSelectionYaw = Integer.MIN_VALUE;
        sectorPitchView = false;
        sectorPitchDrag = -1;
        selectedSector = index;
        feedback = "";
        signature = "";
    }

    private void saveSector() {
        var turret = arcTurret();
        if (turret == null || sectorFields[0] == null) return;
        try {
            int yaw = readSectorYaw(0);
            int end = readSectorYaw(1);
            int lower = Integer.parseInt(sectorFields[2].getValue());
            int upper = Integer.parseInt(sectorFields[3].getValue());
            boolean enabled = turret.sectors().endpoints().get(selectedSector).enabled();
            var endpoint = new SectorArc.Endpoint(yaw, lower, upper, enabled);
            var replacement = turret.sectors().editSector(selectedSector, endpoint, end);
            if (!turret.sectors().equals(replacement)) sectorSelectionYaw = endpoint.yaw();
            sendSectorArc(turret, replacement);
        } catch (IllegalArgumentException invalid) {
            feedback = "检查角度范围、俯仰上下限和端点顺序";
        }
    }

    private int readSectorYaw(int field) {
        int yaw = Integer.parseInt(sectorFields[field].getValue());
        if (yaw < -180 || yaw > 180) throw new IllegalArgumentException("Invalid yaw");
        return (int) GimbalMath.wrapDeg(yaw * yawRightSign());
    }

    private void sendSectorArc(FireControlIdentityPayload.Turret turret, SectorArc replacement) {
        feedback = "";
        if (!turret.sectors().equals(replacement)) {
            PacketDistributor.sendToServer(new FireControlSectorPayload(menu.containerId, turret.id(), turret.sectors(), replacement));
        }
    }

    private static final int SECTOR_DIAL_X = DETAIL_X + 24, SECTOR_DIAL_RADIUS = 19;

    private void drawSectors(GuiGraphics g) {
        var turret = arcTurret();
        var points = turret.sectors().endpoints();
        var point = points.get(selectedSector);
        if (sectorPitchView) drawSectorPitch(g, point);
        else drawSectorYaw(g, points);

        for (int i = 0; i < SECTOR_FIELDS.length; i++) {
            String label = sectorFieldWidth() >= 34 ? SECTOR_FIELDS[i] : SECTOR_FIELDS[i].substring(2);
            text(g, label, sectorFieldX(i), 151 - sectorLayoutShift(), DIM);
        }
        if (imageHeight >= 238) {
            text(g, (sectorPitchView ? "侧视 · 上仰为正" : yawRightSign() < 0 ? "俯视 · 逆时针分区" : "俯视 · 顺时针分区")
                    + (point.enabled() ? "" : " · 已禁用"), sectorFieldX(), 207, point.enabled() ? DIM : RED);
        }
    }

    private void drawSectorYaw(GuiGraphics g, List<SectorArc.Endpoint> points) {
        var point = points.get(selectedSector);
        int start = sectorDragTurret == null ? point.yaw() : sectorDragYaw;
        int[] displayYaws = new int[points.size()];
        for (int i = 0; i < points.size(); i++) displayYaws[i] = i == selectedSector ? start : points.get(i).yaw();
        for (int i = 0; i < points.size(); i++) {
            int arcStart = displayYaws[i];
            int arcEnd = displayYaws[(i + 1) % points.size()];
            int span = Math.floorMod(arcEnd - arcStart, 360);
            boolean enabled = points.get(i).enabled();
            int color = enabled ? (i == selectedSector ? GREEN : LINE) : RED;
            int dotRadius = !enabled && i == selectedSector ? 1 : 0;
            for (int offset = 0; offset < span; offset += 4) sectorDot(g, arcStart + offset, color, dotRadius);
        }
        for (int i = 0; i < points.size(); i++) {
            sectorDot(g, i == selectedSector ? start : points.get(i).yaw(), i == selectedSector ? TEXT : AMBER, 2);
        }
        if (menu.relYaw10(selected) != FireControlMenu.REL_UNKNOWN) {
            double yaw = Math.toRadians(menu.relYaw10(selected) / 10.0);
            dialNeedle(g, SECTOR_DIAL_X, sectorDialY(), yawRightSign() * Math.sin(yaw), -Math.cos(yaw), SECTOR_DIAL_RADIUS, CYAN);
        }
        text(g, "炮口零位", SECTOR_DIAL_X - 18, sectorDialY() - 32, DIM);
        text(g, "左", SECTOR_DIAL_X - 23, sectorDialY() + 13, DIM);
        text(g, "右", SECTOR_DIAL_X + 14, sectorDialY() + 13, DIM);
    }

    private void drawSectorPitch(GuiGraphics g, SectorArc.Endpoint point) {
        int centerX = SECTOR_DIAL_X - 8, centerY = sectorDialY();
        text(g, "俯仰侧视", SECTOR_DIAL_X - 18, centerY - 32, DIM);
        int lower = sectorPitchValue(2, point.lower()), upper = sectorPitchValue(3, point.upper());
        for (int angle = -90; angle <= 90; angle += 3) {
            int color = angle < lower || angle > upper ? LINE : point.enabled() ? GREEN : RED;
            pitchDot(g, centerX, centerY, angle, color, 0);
        }
        pitchDot(g, centerX, centerY, 0, TEXT, 1);
        pitchDot(g, centerX, centerY, lower, AMBER, 2);
        pitchDot(g, centerX, centerY, upper, AMBER, 2);
        int live = menu.relPitch10(selected);
        if (live != FireControlMenu.REL_UNKNOWN) {
            double radians = Math.toRadians(live / 10.0);
            dialNeedle(g, centerX, centerY, Math.cos(radians), -Math.sin(radians), SECTOR_DIAL_RADIUS, CYAN);
        }
    }

    private int sectorPitchValue(int field, int fallback) {
        if (sectorFields[field] == null) return fallback;
        try {
            return Math.clamp(Integer.parseInt(sectorFields[field].getValue()), -90, 90);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void pitchDot(GuiGraphics g, int centerX, int centerY, double angle, int color, int radius) {
        double radians = Math.toRadians(angle);
        int x = centerX + (int) Math.round(SECTOR_DIAL_RADIUS * Math.cos(radians));
        int y = centerY - (int) Math.round(SECTOR_DIAL_RADIUS * Math.sin(radians));
        g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
    }

    private void dialNeedle(GuiGraphics g, int x, int y, double dx, double dy, int radius, int color) {
        for (int distance = 3; distance <= radius; distance++) {
            int px = x + (int) Math.round(distance * dx), py = y + (int) Math.round(distance * dy);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private void sectorDot(GuiGraphics g, double angle, int color, int radius) {
        double radians = Math.toRadians(angle);
        int x = SECTOR_DIAL_X + (int) Math.round(SECTOR_DIAL_RADIUS * yawRightSign() * Math.sin(radians));
        int y = sectorDialY() - (int) Math.round(SECTOR_DIAL_RADIUS * Math.cos(radians));
        g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
    }
    private int boundValue(int p) {
        if (dragParam == p) return dragValue;
        return switch (p) {
            case 0 -> menu.arcYawMin(selected);
            case 1 -> menu.arcYawMax(selected);
            case 2 -> menu.arcPitchMin(selected);
            default -> menu.arcPitchMax(selected);
        };
    }
    private int yawRightSign() {
        var info = menu.turretInfo(selected);
        return info == null ? -1 : info.yawRightSign();
    }

    private int displayYaw(int internal) {
        return internal == -180 ? -180 : internal * yawRightSign();
    }

    private double pointerYaw(double x, double y) {
        return Math.toDegrees(Math.atan2(x * yawRightSign(), y));
    }

    private String boundText(int p) {
        if (p >= 2) return (p == 2 ? "下 " : "上 ") + boundValue(p) + "°";
        boolean left = (p == 0) == (yawRightSign() > 0);
        return (left ? "左 " : "右 ") + (boundValue(p) * yawRightSign()) + "°";
    }
    private int pointX(boolean yaw, double angle) {
        double radians = Math.toRadians(angle);
        return dialX(yaw) + (int) Math.round(dialRadius() * (yaw ? yawRightSign() * Math.sin(radians) : Math.cos(radians)));
    }
    private int pointY(boolean yaw, double angle) {
        double radians = Math.toRadians(angle);
        return dialY() - (int) Math.round(dialRadius() * (yaw ? Math.cos(radians) : Math.sin(radians)));
    }

    private void drawArcs(GuiGraphics g) {
        text(g, "偏航 · 炮口零位↑", DETAIL_X, 144, DIM);
        text(g, "俯仰 · 侧视", DETAIL_X + contentWidth() / 2, 144, DIM);
        for (int axis = 0; axis < 2; axis++) {
            boolean yaw = axis == 0;
            int min = boundValue(axis * 2), max = boundValue(axis * 2 + 1);
            for (int angle = yaw ? -180 : -90; angle <= (yaw ? 180 : 90); angle += 2) {
                int color = angle >= min && angle <= max ? GREEN : LINE;
                int x = pointX(yaw, angle), y = pointY(yaw, angle);
                g.fill(x, y, x + 1, y + 1, color);
            }
            int zeroX = pointX(yaw, 0), zeroY = pointY(yaw, 0);
            g.fill(zeroX - 1, zeroY - 1, zeroX + 2, zeroY + 2, TEXT);
            int live = yaw ? menu.relYaw10(selected) : menu.relPitch10(selected);
            if (live != FireControlMenu.REL_UNKNOWN) {
                int lx = pointX(yaw, live / 10.0), ly = pointY(yaw, live / 10.0);
                dialNeedle(g, dialX(yaw), dialY(), (lx - dialX(yaw)) / (double) dialRadius(),
                        (ly - dialY()) / (double) dialRadius(), dialRadius(), CYAN);
                g.fill(lx - 2, ly - 2, lx + 3, ly + 3, CYAN);
            }
            for (int p = axis * 2; p < axis * 2 + 2; p++) {
                int hx = pointX(yaw, boundValue(p)), hy = pointY(yaw, boundValue(p));
                g.fill(hx - 3, hy - 3, hx + 4, hy + 4, activeBound == p ? TEXT : AMBER);
            }
            if (imageHeight >= 260) text(g, "安装零位为 0°", dialX(yaw) - 36, dialY() + dialRadius() + 12, DIM);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        var turret = arcTurret();
        if (pendingAction < 0 && turret != null && turret.sectors().enabled() && sectorPitchView && button == 0) {
            double x = mouseX - leftPos - (SECTOR_DIAL_X - 8), y = sectorDialY() - (mouseY - topPos);
            var point = turret.sectors().endpoints().get(selectedSector);
            double lower = Math.toRadians(sectorPitchValue(2, point.lower()));
            double upper = Math.toRadians(sectorPitchValue(3, point.upper()));
            double lowDistance = Math.hypot(x - SECTOR_DIAL_RADIUS * Math.cos(lower), y - SECTOR_DIAL_RADIUS * Math.sin(lower));
            double highDistance = Math.hypot(x - SECTOR_DIAL_RADIUS * Math.cos(upper), y - SECTOR_DIAL_RADIUS * Math.sin(upper));
            if (Math.min(lowDistance, highDistance) <= 6) {
                sectorPitchDrag = lowDistance <= highDistance ? 2 : 3;
                return true;
            }
        }
        if (pendingAction < 0 && turret != null && turret.sectors().enabled() && !sectorPitchView && button == 0) {
            double x = mouseX - leftPos - SECTOR_DIAL_X, y = sectorDialY() - (mouseY - topPos);
            double radius = Math.hypot(x, y);
            if (radius >= SECTOR_DIAL_RADIUS - 6 && radius <= SECTOR_DIAL_RADIUS + 6) {
                var points = turret.sectors().endpoints();
                double yaw = pointerYaw(x, y);
                for (int i = 0; i < points.size(); i++) {
                    double delta = Math.abs(GimbalMath.wrapDeg(yaw - points.get(i).yaw()));
                    if (delta <= 9) {
                        selectSector(i);
                        sectorDragTurret = turret;
                        sectorDragYaw = points.get(i).yaw();
                        return true;
                    }
                }
                int nearest = 0;
                for (int i = 1; i < points.size(); i++) {
                    if (GimbalMath.norm360(yaw - points.get(i).yaw()) < GimbalMath.norm360(yaw - points.get(nearest).yaw())) {
                        nearest = i;
                    }
                }
                selectSector(nearest);
                return true;
            }
        }
        if (pendingAction < 0 && page == 0 && detail == ARC_DETAIL && hasSelection() && !sectorMode() && button == 0) {
            double x = mouseX - leftPos, y = mouseY - topPos;
            int best = -1;
            double distance = 81;
            // Prefer the explicitly selected bound when two endpoints overlap.
            for (int offset = 0; offset < 4; offset++) {
                int p = (activeBound + offset) % 4;
                double dx = x - pointX(p < 2, boundValue(p)), dy = y - pointY(p < 2, boundValue(p));
                double d = dx * dx + dy * dy;
                if (d < distance) { best = p; distance = d; }
            }
            if (best >= 0) {
                int initial = boundValue(best);
                activeBound = best; dragParam = best; dragValue = initial; dragEpoch = menu.rosterEpoch();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (sectorPitchDrag >= 0 && button == 0) {
            var turret = arcTurret();
            if (turret == null || !turret.sectors().enabled() || sectorFields[sectorPitchDrag] == null) {
                sectorPitchDrag = -1;
                return true;
            }
            var point = turret.sectors().endpoints().get(selectedSector);
            double x = mouseX - leftPos - (SECTOR_DIAL_X - 8), y = sectorDialY() - (mouseY - topPos);
            if (Math.hypot(x, y) >= 5) {
                int pitch = (int) Math.round(Math.toDegrees(Math.atan2(y, Math.max(0, x))));
                pitch = sectorPitchDrag == 2 ? Math.min(pitch, sectorPitchValue(3, point.upper()))
                        : Math.max(pitch, sectorPitchValue(2, point.lower()));
                sectorFields[sectorPitchDrag].setValue(Integer.toString(pitch));
            }
            return true;
        }
        if (sectorDragTurret != null && button == 0) {
            var points = sectorDragTurret.sectors().endpoints();
            double x = mouseX - leftPos - SECTOR_DIAL_X, y = sectorDialY() - (mouseY - topPos);
            if (Math.hypot(x, y) >= 6) {
                int yaw = (int) GimbalMath.wrapDeg(Math.round(pointerYaw(x, y)));
                int previous = points.get(Math.floorMod(selectedSector - 1, points.size())).yaw();
                int next = points.get((selectedSector + 1) % points.size()).yaw();
                int span = Math.floorMod(next - previous, 360);
                int offset = Math.floorMod(yaw - previous, 360);
                if (offset > 0 && (span == 0 || offset < span)) sectorDragYaw = yaw;
            }
            return true;
        }
        if (dragParam < 0) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        // Roster changed mid-drag: further sends would be rejected with a chat
        // line each, so end the drag instead of spamming.
        if (sectorMode() || menu.rosterEpoch() != dragEpoch) { dragParam = -1; return super.mouseDragged(mouseX, mouseY, button, dx, dy); }
        boolean yaw = dragParam < 2;
        double x = mouseX - leftPos - dialX(yaw), y = dialY() - (mouseY - topPos);
        int angle = (int) Math.round(yaw ? pointerYaw(x, y) : Math.toDegrees(Math.atan2(y, x)));
        int value = Math.max(yaw ? -180 : -90, Math.min(yaw ? 180 : 90, angle));
        if (value != dragValue) {
            dragValue = value;
            send(FireControlMenu.ARC_SET_BASE + selected * 4096 + dragParam * 512 + value + 180);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (sectorPitchDrag >= 0 && button == 0) {
            sectorPitchDrag = -1;
            return true;
        }
        if (sectorDragTurret != null && button == 0) {
            var turret = sectorDragTurret;
            sectorDragTurret = null;
            var current = arcTurret();
            if (current != null && turret.id().equals(current.id()) && turret.sectors().equals(current.sectors())
                    && turret.yawRightSign() == current.yawRightSign()) {
                var endpoint = turret.sectors().endpoints().get(selectedSector);
                if (endpoint.yaw() != sectorDragYaw) {
                    sectorSelectionYaw = sectorDragYaw;
                    sendSectorArc(turret, turret.sectors().replace(selectedSector,
                            new SectorArc.Endpoint(sectorDragYaw, endpoint.lower(), endpoint.upper(), endpoint.enabled())));
                }
            }
            return true;
        }
        if (dragParam >= 0) { dragParam = -1; signature = ""; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page == STRIKE_PAGE && vertical != 0 && mouseY >= topPos + STRIKE_ROWS_Y
                && mouseY < topPos + imageHeight - 26 && mouseX >= leftPos && mouseX < leftPos + imageWidth) {
            strikeOffset = Math.max(0, Math.min(Math.max(0, strikeRows.size() - strikeVisibleRows()),
                    strikeOffset + (vertical > 0 ? -1 : 1)));
            return true;
        }
        if (page == 0 && pendingAction < 0 && mouseX >= leftPos + 10 && mouseX < leftPos + 110 && vertical != 0) {
            listOffset = Math.max(0, Math.min(Math.max(0, memberCount() - visibleRows()), listOffset + (vertical > 0 ? -1 : 1)));
            signature = ""; return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (turretNameEdit != null && turretNameEdit.isFocused() && key != 256 && key != 258) {
            if (key == 257 || key == 335) saveTurretName();
            else turretNameEdit.keyPressed(key, scan, modifiers);
            return true;
        }
        if (sectorMode() && getFocused() instanceof EditBox box && key != 256 && key != 258) {
            if (key == 257 || key == 335) saveSector();
            else box.keyPressed(key, scan, modifiers);
            return true;
        }
        if (page == STRIKE_PAGE && getFocused() instanceof EditBox box && key != 256 && key != 258) {
            if (key == 257 || key == 335) sendStrike(FireControlStrikeRequest.Action.PREVIEW);
            else box.keyPressed(key, scan, modifiers);
            return true;
        }
        if (page == 1 && groupNameEdit != null && groupNameEdit.isFocused() && key != 256 && key != 258) {
            if (key == 257 || key == 335) saveGroupName();
            else groupNameEdit.keyPressed(key, scan, modifiers);
            return true;
        }
        if (key == 256 && groupPicker) { groupPicker = false; signature = ""; return true; }
        if (key == 256 && pendingAction >= 0) { pendingAction = -1; signature = ""; return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    private void text(GuiGraphics g, String text, int x, int y, int color) { g.drawString(font, text, x, y, color, false); }
    private void right(GuiGraphics g, String text, int x, int y, int color) { text(g, text, x - font.width(text), y, color); }
    private String fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
    private void wrapped(GuiGraphics g, String text, int x, int y, int width, int color) {
        for (var line : font.split(Component.literal(text), width)) {
            g.drawString(font, line, x, y, color, false); y += 11;
        }
    }

    private final class FlatButton extends Button {
        private final boolean chosen;
        FlatButton(int x, int y, int w, int h, Component label, OnPress action, boolean chosen) {
            super(x, y, w, h, label, action, DEFAULT_NARRATION);
            this.chosen = chosen;
        }
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hover = isHoveredOrFocused();
            int fill = !active ? BG : chosen ? 0xFF254C5C : hover ? 0xFF2A3E50 : PANEL;
            int border = active && (chosen || hover) ? CYAN : LINE;
            int color = !active ? 0xFF6C7F91 : chosen ? CYAN : TEXT;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            g.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
            String label = fit(getMessage().getString(), getWidth() - 6);
            g.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - 8) / 2, color, false);
        }
    }
}
