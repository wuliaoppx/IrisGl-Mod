package dev.antimony.core.guard;

import dev.antimony.AntimonyMod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-game face of {@link OpenGlGuard}: lists the mods that talk to OpenGL
 * and lets the player disable them with one click.
 *
 * <p>Disabling is reversible and non-destructive - the jar is moved into
 * {@code mods/.antimony-disabled/} with a {@code .disabled} suffix, never
 * deleted. The same screen flips into a restore list, so a wrong guess costs
 * one click rather than a re-download.</p>
 *
 * <p>Every pixel here is drawn by the IrisGL software rasterizer, unlike the
 * native message box this replaces.</p>
 */
public final class GuardScreen extends Screen {
    private enum Mode {
        REPORT, RESTORE
    }

    private final List<GuardFinding> report;
    private List<ModDisabler.DisabledMod> disabled;
    private Mode mode;
    private EntryList list;
    private Button actionButton;
    private Button modeButton;
    private String status = "";

    public GuardScreen() {
        super(Component.literal("Antimony - OpenGL 兼容性检查"));
        this.report = OpenGlGuard.findings();
        this.disabled = ModDisabler.listDisabled();
        this.mode = this.report.isEmpty() && !this.disabled.isEmpty() ? Mode.RESTORE : Mode.REPORT;
    }

    @Override
    protected void init() {
        this.list = new EntryList(this.minecraft);
        this.rebuildRows();
        this.addRenderableWidget(this.list);

        int centre = this.width / 2;
        int row1 = this.height - 54;
        int row2 = this.height - 28;
        this.actionButton = this.addRenderableWidget(Button.builder(this.actionLabel(), b -> this.apply())
                .bounds(centre - 155, row1, 150, 20).build());
        this.modeButton = this.addRenderableWidget(Button.builder(this.modeLabel(), b -> this.toggleMode())
                .bounds(centre + 5, row1, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("本次忽略"), b -> {
            OpenGlGuard.ignoreThisSession();
            this.onClose();
        }).bounds(centre - 155, row2, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("打开报告"), b -> this.openReport())
                .bounds(centre - 80, row2, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("mods 目录"), b -> this.openMods())
                .bounds(centre - 5, row2, 80, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(centre + 80, row2, 70, 20).build());
    }

    private Component actionLabel() {
        return Component.literal(this.mode == Mode.REPORT ? "禁用选中并重启" : "恢复选中并重启");
    }

    private Component modeLabel() {
        return Component.literal(this.mode == Mode.REPORT
                ? "查看已禁用 (" + this.disabled.size() + ")"
                : "返回检查结果");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFF469DC9);
        String hint = this.mode == Mode.REPORT
                ? "点一行选中要禁用的模组；禁用只移动文件，不会删除"
                : "点一行选中要恢复的模组；恢复后重启才会加载";
        graphics.drawCenteredString(this.font, Component.literal(hint), this.width / 2, 28, 0xFF9A9A9A);
        if (!this.status.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(this.status), this.width / 2,
                    this.height - 68, 0xFFFF7070);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Flat dim instead of vanilla's blur + gradient: blur needs several GL
        // passes we do not have, and a flat layer keeps the text readable no
        // matter what the world behind it looks like.
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.list != null && this.list.isMouseOver(mouseX, mouseY)
                && this.list.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    private void toggleMode() {
        this.mode = this.mode == Mode.REPORT ? Mode.RESTORE : Mode.REPORT;
        this.disabled = ModDisabler.listDisabled();
        this.status = "";
        this.rebuildRows();
        if (this.actionButton != null) {
            this.actionButton.setMessage(this.actionLabel());
        }
        if (this.modeButton != null) {
            this.modeButton.setMessage(this.modeLabel());
        }
    }

    private void rebuildRows() {
        if (this.list != null) {
            this.list.rebuild();
        }
    }

    private void apply() {
        List<Row> selected = new ArrayList<>();
        for (Row row : this.list.children()) {
            if (row.selected) {
                selected.add(row);
            }
        }
        if (selected.isEmpty()) {
            this.status = "没有选中任何项目";
            return;
        }
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (Row row : selected) {
            ModDisabler.Result result = this.mode == Mode.REPORT
                    ? ModDisabler.disable(row.key)
                    : ModDisabler.restore(row.key);
            if (result == ModDisabler.Result.OK) {
                ok++;
            } else {
                failures.add(row.title + " (" + result.name() + ")");
            }
        }
        if (!failures.isEmpty()) {
            this.status = "部分失败: " + String.join(", ", failures);
            this.disabled = ModDisabler.listDisabled();
            this.rebuildRows();
            return;
        }
        AntimonyMod.LOG.info("[Antimony] guard: {} mod(s) {} - restarting", ok,
                this.mode == Mode.REPORT ? "disabled" : "restored");
        GuardRestart.restart(this.minecraft);
    }

    private void openReport() {
        Path file = OpenGlGuard.reportPath();
        if (Files.isRegularFile(file)) {
            Util.getPlatform().openFile(file.toFile());
        } else {
            this.status = "还没有生成报告";
        }
    }

    private void openMods() {
        try {
            Util.getPlatform().openFile(ModDisabler.modsDir().toFile());
        } catch (RuntimeException e) {
            this.status = "打不开 mods 目录";
        }
    }

    // ---- list ----------------------------------------------------------

    private final class EntryList extends ObjectSelectionList<Row> {
        EntryList(Minecraft mc) {
            super(mc, GuardScreen.this.width, GuardScreen.this.height - 96, 46, 34);
        }

        @Override
        public int getRowWidth() {
            return GuardScreen.this.width - 40;
        }

        @Override
        protected int getScrollbarPosition() {
            return GuardScreen.this.width - 26;
        }

        /** Vanilla blits a white translucent texture here; we want a flat panel. */
        @Override
        protected void renderListBackground(GuiGraphics graphics) {
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(this.getX(), this.getY(), this.getX() + this.width,
                    this.getY() + this.height, 0xE0000000);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }

        void rebuild() {
            this.clearEntries();
            if (GuardScreen.this.mode == Mode.REPORT) {
                for (GuardFinding finding : GuardScreen.this.report) {
                    List<String> symbols = finding.symbols();
                    String joined = symbols.isEmpty() ? "无"
                            : String.join(", ", symbols.subList(0, Math.min(2, symbols.size())))
                              + (symbols.size() > 2 ? " +" + (symbols.size() - 2) : "");
                    String detail = finding.hard()
                            ? "直接调用 OpenGL: " + joined
                            : "只引用了 org.lwjgl.opengl，没有自己的绘制调用";
                    this.addEntry(new Row(finding.fileName(), finding.label(), detail, finding.hard()));
                }
            } else {
                for (ModDisabler.DisabledMod mod : GuardScreen.this.disabled) {
                    String when = mod.disabledAt().isEmpty() ? "已禁用" : "已禁用 " + mod.disabledAt();
                    this.addEntry(new Row(mod.stored(), mod.original(), when, false));
                }
            }
        }
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final String key;
        private final String title;
        private final String detail;
        private final boolean hard;
        private boolean selected;

        Row(String key, String title, String detail, boolean hard) {
            this.key = key;
            this.title = title;
            this.detail = detail;
            this.hard = hard;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean over = mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
            if (over) {
                graphics.fill(left, top, left + width, top + height, 0x30FFFFFF);
            }
            int boxX = left + 4;
            int boxY = top + 11;
            graphics.fill(boxX, boxY, boxX + 10, boxY + 10, 0xFF7A7A7A);
            graphics.fill(boxX + 1, boxY + 1, boxX + 9, boxY + 9,
                    this.selected ? 0xFF469DC9 : 0xFF1A1A1A);
            if (this.selected) {
                graphics.fill(boxX + 3, boxY + 3, boxX + 7, boxY + 7, 0xFF08202B);
            }
            int textX = boxX + 16;
            int maxWidth = Math.max(20, left + width - textX - 6);
            Font font = GuardScreen.this.font;
            graphics.drawString(font, font.plainSubstrByWidth(this.title, maxWidth), textX, top + 5,
                    this.hard ? 0xFFFFB4B4 : 0xFFE8E8E8, false);
            graphics.drawString(font, font.plainSubstrByWidth(this.detail, maxWidth), textX, top + 18,
                    0xFF8C8C8C, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                this.selected = !this.selected;
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.title);
        }
    }
}
