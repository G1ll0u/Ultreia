package com.jubitus.millpathbuilder.gui;

import com.jubitus.millpathbuilder.config.ConfigEntry;
import com.jubitus.millpathbuilder.config.MPBConfigReflector;
import com.jubitus.millpathbuilder.network.MPBNetworkHandler;
import com.jubitus.millpathbuilder.network.PacketSaveConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@SideOnly(Side.CLIENT)
public final class GuiMPBConfig extends GuiScreen {

    private static final int ROW_H        = 20;
    private static final int LIST_TOP     = 58;
    private static final int LIST_BOT_PAD = 34;
    private static final int SIDE_PAD     = 8;
    private static final int VALUE_W      = 110;
    private static final int HIST_SQ      = 8;
    private static final int SCROLLBAR_W  = 4;

    // Beige / warm brown theme
    private static final int C_TITLE      = 0xFFD4B870;
    private static final int C_SEPARATOR  = 0xFF443322;
    private static final int C_CAT_BG     = 0x99331A00;
    private static final int C_CAT_TEXT   = 0xFFE8C87A;
    private static final int C_ROW_HOVER  = 0x22C8A060;
    private static final int C_ROW_SEL    = 0x55D4A870;
    private static final int C_LABEL      = 0xFFCCAA66;
    private static final int C_LABEL_SEL  = 0xFFFFD890;
    private static final int C_VAL_BORDER = 0xFF665533;
    private static final int C_VAL_BG     = 0xFF100C05;
    private static final int C_VAL_TEXT   = 0xFFCCAA66;
    private static final int C_MUTED      = 0xFF887744;
    private static final int C_SCROLL_TRK = 0xFF1A1208;
    private static final int C_SCROLL_THB = 0xFF886644;
    private static final int C_BOOL_ON    = 0xFF2A4A1A;
    private static final int C_BOOL_OFF   = 0xFF4A1A0A;

    private final List<ConfigEntry> allEntries;
    private List<ConfigEntry> filteredEntries;
    private List<Object> displayRows; // String = category header, ConfigEntry = field

    private GuiTextField searchField;
    private GuiTextField valueField;
    private GuiTextField histSizeField;

    private int scrollOffset = 0;
    private int selectedRow  = -1;

    public GuiMPBConfig(Map<String, String> serverValues) {
        List<ConfigEntry> entries = MPBConfigReflector.buildEntries();
        for (ConfigEntry e : entries) {
            String v = serverValues.get(e.path);
            if (v != null) e.currentValue = v;
        }
        this.allEntries = entries;
        this.filteredEntries = new ArrayList<>(entries);
        rebuildDisplayRows();
        this.scrollOffset = MPBGuiState.lastScrollOffset;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int sortBtnW = 44;
        String histLbl = "Hist:";
        int histLblW  = fontRenderer.getStringWidth(histLbl) + 4;
        int histFldW  = 28;
        String srchLbl = "Search:";
        int srchLblW  = fontRenderer.getStringWidth(srchLbl) + 4;

        int sfX    = SIDE_PAD + sortBtnW + 6 + srchLblW;
        int sfRight = width - SIDE_PAD - histFldW - 4 - histLblW - 50;
        int sfW    = Math.max(60, sfRight - sfX);

        searchField = new GuiTextField(0, fontRenderer, sfX, 32, sfW, 18);
        searchField.setMaxStringLength(64);
        searchField.setText(MPBGuiState.lastSearch);
        searchField.setFocused(true);

        int histFldX = width - SIDE_PAD - histFldW;
        histSizeField = new GuiTextField(2, fontRenderer, histFldX, 32, histFldW, 18);
        histSizeField.setMaxStringLength(3);
        histSizeField.setText(String.valueOf(MPBGuiState.historyMaxSize));

        // valueField bounds exactly match the static value box so no double-border appears
        int vx = valueX();
        valueField = new GuiTextField(1, fontRenderer, vx, 0, VALUE_W, ROW_H - 6);
        valueField.setMaxStringLength(512);

        int btnY = height - 26;
        buttonList.add(new GuiButton(1, width / 2 - 105, btnY, 100, 20, "Save"));
        buttonList.add(new GuiButton(2, width / 2 + 5,   btnY, 100, 20, "Cancel"));
        buttonList.add(new GuiButton(3, SIDE_PAD, 28, sortBtnW, 18,
                MPBGuiState.sortByModification ? "§eSorted" : "Sort"));

        if (!MPBGuiState.lastSearch.isEmpty()) applyFilter(MPBGuiState.lastSearch);

        // Restore previous selection
        if (MPBGuiState.lastSelectedPath != null) {
            for (int i = 0; i < displayRows.size(); i++) {
                Object item = displayRows.get(i);
                if (item instanceof ConfigEntry
                        && ((ConfigEntry) item).path.equals(MPBGuiState.lastSelectedPath)) {
                    selectedRow = i;
                    ConfigEntry e = (ConfigEntry) item;
                    if (!e.isBoolean()) {
                        valueField.setText(e.currentValue);
                        valueField.setFocused(true);
                        searchField.setFocused(false);
                    }
                    break;
                }
            }
        }
        clampScroll();
    }

    @Override
    public void onGuiClosed() {
        MPBGuiState.lastSearch = searchField != null ? searchField.getText() : "";
        MPBGuiState.lastScrollOffset = scrollOffset;
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private int valueX()  { return width - VALUE_W - SIDE_PAD - SCROLLBAR_W - 2; }
    private int histSqX() { return valueX() - HIST_SQ - 6; }
    private int listBot() { return height - LIST_BOT_PAD; }
    private int visibleRows() { return Math.max(1, (listBot() - LIST_TOP) / ROW_H); }
    private int maxScroll()   { return Math.max(0, displayRows.size() - visibleRows()); }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    private int rowYOnScreen(int idx) { return LIST_TOP + (idx - scrollOffset) * ROW_H; }

    private boolean rowVisible(int idx) {
        int y = rowYOnScreen(idx);
        return y >= LIST_TOP && y + ROW_H <= listBot();
    }

    // -------------------------------------------------------------------------
    // Display rows
    // -------------------------------------------------------------------------

    private void rebuildDisplayRows() {
        List<Object> rows = new ArrayList<>();
        List<ConfigEntry> source = filteredEntries;

        if (MPBGuiState.sortByModification) {
            List<String> order = MPBGuiState.getModificationOrder();
            List<ConfigEntry> modified   = new ArrayList<>();
            List<ConfigEntry> unmodified = new ArrayList<>();
            for (ConfigEntry e : source) {
                if (order.contains(e.path)) modified.add(e);
                else unmodified.add(e);
            }
            modified.sort((a, b) -> order.indexOf(b.path) - order.indexOf(a.path));
            source = new ArrayList<>(modified);
            source.addAll(unmodified);
            for (ConfigEntry e : source) rows.add(e);
        } else {
            String prev = null;
            for (ConfigEntry e : source) {
                if (!e.category.equals(prev)) { rows.add(e.category); prev = e.category; }
                rows.add(e);
            }
        }
        displayRows = rows;
    }

    // -------------------------------------------------------------------------
    // Filter
    // -------------------------------------------------------------------------

    private void applyFilter(String query) {
        query = query.toLowerCase(Locale.ROOT).trim();
        if (query.isEmpty()) {
            filteredEntries = new ArrayList<>(allEntries);
        } else {
            final String q = query;
            filteredEntries = allEntries.stream()
                    .filter(e -> e.name.toLowerCase(Locale.ROOT).contains(q)
                            || e.category.toLowerCase(Locale.ROOT).contains(q)
                            || e.comment.toLowerCase(Locale.ROOT).contains(q))
                    .collect(Collectors.toList());
        }
        rebuildDisplayRows();
        scrollOffset = 0;
        deselect();
    }

    // -------------------------------------------------------------------------
    // Selection / editing
    // -------------------------------------------------------------------------

    private void deselect() {
        selectedRow = -1;
        MPBGuiState.lastSelectedPath = null;
    }

    private void selectRow(int idx) {
        if (idx < 0 || idx >= displayRows.size()) return;
        Object item = displayRows.get(idx);
        if (!(item instanceof ConfigEntry)) return;
        ConfigEntry e = (ConfigEntry) item;
        if (e.isBoolean()) {
            commitEdit();
            String old = e.currentValue;
            e.currentValue = String.valueOf(!Boolean.parseBoolean(e.currentValue));
            MPBGuiState.recordChange(e.path, old, e.currentValue);
            deselect();
        } else {
            commitEdit();
            selectedRow = idx;
            MPBGuiState.lastSelectedPath = e.path;
            valueField.setText(e.currentValue);
            valueField.setFocused(true);
            searchField.setFocused(false);
        }
    }

    private void commitEdit() {
        if (selectedRow < 0 || selectedRow >= displayRows.size()) return;
        Object item = displayRows.get(selectedRow);
        if (!(item instanceof ConfigEntry)) return;
        ConfigEntry e = (ConfigEntry) item;
        String raw = valueField.getText().trim();
        if (!raw.isEmpty()) {
            String old = e.currentValue;
            if (MPBConfigReflector.applyValue(e, raw)) {
                MPBGuiState.recordChange(e.path, old, e.currentValue);
            }
        }
    }

    private void advanceSelection(int dir) {
        int start = selectedRow < 0 ? scrollOffset : selectedRow;
        for (int off = 1; off <= displayRows.size(); off++) {
            int next = start + dir * off;
            if (next < 0 || next >= displayRows.size()) break;
            Object item = displayRows.get(next);
            if (item instanceof ConfigEntry && !((ConfigEntry) item).isBoolean()) {
                selectedRow = next;
                MPBGuiState.lastSelectedPath = ((ConfigEntry) item).path;
                if (!rowVisible(next)) {
                    scrollOffset = Math.max(0, next - visibleRows() / 2);
                    clampScroll();
                }
                ConfigEntry e = (ConfigEntry) item;
                valueField.setText(e.currentValue);
                valueField.setFocused(true);
                return;
            }
        }
        deselect();
    }

    private void applyHistSize() {
        try {
            int v = Integer.parseInt(histSizeField.getText().trim());
            v = Math.max(1, Math.min(100, v));
            MPBGuiState.historyMaxSize = v;
            MPBGuiState.trimHistoryToMax();
            histSizeField.setText(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            histSizeField.setText(String.valueOf(MPBGuiState.historyMaxSize));
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int vx   = valueX();
        int hsqX = histSqX();
        int lTop = LIST_TOP;
        int lBot = listBot();

        // Title
        drawCenteredString(fontRenderer, "§l§6MillPathBuilder Config", width / 2, 10, C_TITLE);

        // Search
        String srchLbl = "Search:";
        drawString(fontRenderer, srchLbl,
                searchField.x - fontRenderer.getStringWidth(srchLbl) - 3, 37, C_MUTED);
        searchField.drawTextBox();

        // Count hint
        String hint = filteredEntries.size() + "/" + allEntries.size();
        drawString(fontRenderer, "§7" + hint,
                searchField.x + searchField.width + 4, 37, C_MUTED);

        // History label + field
        String histLbl = "Hist:";
        drawString(fontRenderer, histLbl,
                histSizeField.x - fontRenderer.getStringWidth(histLbl) - 3, 37, C_MUTED);
        histSizeField.drawTextBox();

        // Separator
        drawRect(SIDE_PAD, lTop - 3, width - SIDE_PAD, lTop - 2, C_SEPARATOR);

        // Scrollbar track
        drawRect(width - SCROLLBAR_W, lTop, width, lBot, C_SCROLL_TRK);

        // Scissor clip for the list
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, mc.displayHeight - lBot * sf, mc.displayWidth, (lBot - lTop) * sf);

        String pendingComment   = null;
        String pendingHistTip   = null;
        int    tipX = 0, tipY  = 0;

        for (int i = 0; i < displayRows.size(); i++) {
            int rowY = rowYOnScreen(i);
            if (rowY + ROW_H <= lTop || rowY >= lBot) continue;

            Object item = displayRows.get(i);

            if (item instanceof String) {
                String cat = (String) item;
                drawRect(SIDE_PAD, rowY + 2,
                        width - SIDE_PAD - SCROLLBAR_W - 2, rowY + ROW_H - 2, C_CAT_BG);
                drawCenteredString(fontRenderer, "§6§l" + cat,
                        width / 2, rowY + 6, C_CAT_TEXT);

            } else {
                ConfigEntry e = (ConfigEntry) item;
                boolean sel     = (i == selectedRow);
                boolean hovered = mouseX >= SIDE_PAD
                        && mouseX < width - SIDE_PAD - SCROLLBAR_W - 2
                        && mouseY >= rowY && mouseY < rowY + ROW_H;

                // Hover on history square?
                int sqY = rowY + (ROW_H - HIST_SQ) / 2;
                int hc  = MPBGuiState.getHistoryCount(e.path);
                boolean overHist = hc > 0
                        && mouseX >= hsqX && mouseX < hsqX + HIST_SQ
                        && mouseY >= sqY   && mouseY < sqY + HIST_SQ;

                // Row background
                if (sel) {
                    drawRect(SIDE_PAD, rowY, vx - 2, rowY + ROW_H, C_ROW_SEL);
                } else if (hovered) {
                    drawRect(SIDE_PAD, rowY, vx - 2, rowY + ROW_H, C_ROW_HOVER);
                }

                // Comment tooltip (not when over history square)
                if (hovered && !overHist && !e.comment.isEmpty()) {
                    pendingComment = buildComment(e);
                    tipX = mouseX; tipY = mouseY;
                }

                // Label
                int maxLblW = hsqX - SIDE_PAD - 12;
                String label = e.name;
                while (!label.isEmpty() && fontRenderer.getStringWidth(label) > maxLblW)
                    label = label.substring(0, label.length() - 1);
                if (label.length() < e.name.length()) label += "..";
                drawString(fontRenderer, label, SIDE_PAD + 4, rowY + 6,
                        sel ? C_LABEL_SEL : C_LABEL);

                // History square
                if (hc > 0) {
                    float fill = Math.min(1f, (float) hc / MPBGuiState.historyMaxSize);
                    int r = (int)(0x99 - (0x99 - 0x44) * fill);
                    int g = (int)(0x77 - (0x77 - 0x22) * fill);
                    int b = (int)(0x44 - (0x44 - 0x00) * fill);
                    int sqColor = 0xFF000000 | (r << 16) | (g << 8) | b;
                    drawRect(hsqX,     sqY,         hsqX + HIST_SQ, sqY + HIST_SQ, sqColor);
                    drawRect(hsqX - 1, sqY - 1,     hsqX + HIST_SQ + 1, sqY,           0xFF332200);
                    drawRect(hsqX - 1, sqY + HIST_SQ, hsqX + HIST_SQ + 1, sqY + HIST_SQ + 1, 0xFF332200);
                    drawRect(hsqX - 1, sqY,         hsqX,           sqY + HIST_SQ, 0xFF332200);
                    drawRect(hsqX + HIST_SQ, sqY,   hsqX + HIST_SQ + 1, sqY + HIST_SQ, 0xFF332200);

                    if (overHist) {
                        List<String> hist = MPBGuiState.getHistory(e.path);
                        StringBuilder sb = new StringBuilder();
                        for (String h : hist) sb.append(h).append(" §7>§r ");
                        sb.append("§a").append(e.currentValue);
                        pendingHistTip = sb.toString();
                        tipX = mouseX; tipY = mouseY;
                    }
                }

                // Value area
                if (e.isBoolean()) {
                    boolean val = Boolean.parseBoolean(e.currentValue);
                    drawRect(vx - 1, rowY + 2, vx + VALUE_W + 1, rowY + ROW_H - 2, C_VAL_BORDER);
                    drawRect(vx,     rowY + 3, vx + VALUE_W,     rowY + ROW_H - 3, val ? C_BOOL_ON : C_BOOL_OFF);
                    drawCenteredString(fontRenderer, val ? "§a ON " : "§c OFF ",
                            vx + VALUE_W / 2, rowY + 6, 0xFFFFFF);
                } else {
                    // Static box (shown when not selected; GuiTextField overwrites when selected)
                    drawRect(vx - 1, rowY + 2, vx + VALUE_W + 1, rowY + ROW_H - 2, C_VAL_BORDER);
                    drawRect(vx,     rowY + 3, vx + VALUE_W,     rowY + ROW_H - 3, C_VAL_BG);
                    if (!sel) {
                        String disp = e.currentValue;
                        int maxW = VALUE_W - 4;
                        while (!disp.isEmpty() && fontRenderer.getStringWidth(disp) > maxW)
                            disp = disp.substring(0, disp.length() - 1);
                        if (disp.length() < e.currentValue.length()) disp += "..";
                        drawString(fontRenderer, disp, vx + 3, rowY + 6, C_VAL_TEXT);
                    }
                    // Selected: GuiTextField drawn outside scissor below
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.disableBlend();

        // Active value field — drawn outside scissor so its own scissor clipping works
        if (selectedRow >= 0 && selectedRow < displayRows.size()) {
            Object item = displayRows.get(selectedRow);
            if (item instanceof ConfigEntry && !((ConfigEntry) item).isBoolean()
                    && rowVisible(selectedRow)) {
                valueField.x = vx;
                valueField.y = rowYOnScreen(selectedRow) + 3;
                valueField.drawTextBox();
            }
        }

        // Scrollbar thumb
        int total = displayRows.size();
        if (total > visibleRows()) {
            int totalH = lBot - lTop;
            int thumbH = Math.max(12, totalH * visibleRows() / total);
            int ms     = maxScroll();
            int thumbY = ms > 0 ? lTop + (totalH - thumbH) * scrollOffset / ms : lTop;
            drawRect(width - SCROLLBAR_W, thumbY, width, thumbY + thumbH, C_SCROLL_THB);
        }

        // Tooltips
        GlStateManager.disableBlend();
        if (pendingHistTip != null) {
            drawHoveringText(wrapText(pendingHistTip, 280), tipX, tipY);
        } else if (pendingComment != null) {
            drawHoveringText(wrapText(pendingComment, 280), tipX, tipY);
        }

        // Buttons — reset GL state so they render correctly
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String buildComment(ConfigEntry e) {
        if (!e.hasRange) return e.comment;
        return e.comment + " §8[" + fmtNum(e.rangeMin) + " – " + fmtNum(e.rangeMax) + "]";
    }

    private static String fmtNum(double v) {
        if (v == Double.NEGATIVE_INFINITY) return "-∞";
        if (v == Double.POSITIVE_INFINITY) return "∞";
        return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private List<String> wrapText(String text, int maxPx) {
        String[] words = text.split(" ");
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String test = cur.length() == 0 ? w : cur + " " + w;
            if (fontRenderer.getStringWidth(test) > maxPx && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                if (cur.length() > 0) cur.append(' ');
                cur.append(w);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
        histSizeField.updateCursorCounter();
        if (selectedRow >= 0) valueField.updateCursorCounter();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        // Track if histSizeField loses focus so we can apply the new value
        boolean histWasFocused = histSizeField.isFocused();
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        histSizeField.mouseClicked(mouseX, mouseY, mouseButton);
        if (selectedRow >= 0) valueField.mouseClicked(mouseX, mouseY, mouseButton);

        if (histWasFocused && !histSizeField.isFocused()) applyHistSize();

        int lTop = LIST_TOP;
        int lBot = listBot();
        int vx   = valueX();

        if (mouseY >= lTop && mouseY < lBot
                && mouseX >= SIDE_PAD && mouseX < width - SIDE_PAD - SCROLLBAR_W - 2) {
            int idx = scrollOffset + (mouseY - lTop) / ROW_H;
            if (idx >= 0 && idx < displayRows.size()) {
                Object item = displayRows.get(idx);
                if (item instanceof ConfigEntry) {
                    ConfigEntry e = (ConfigEntry) item;
                    if (e.isBoolean()) {
                        selectRow(idx);
                    } else {
                        selectRow(idx);
                        if (selectedRow == idx) valueField.mouseClicked(mouseX, mouseY, mouseButton);
                    }
                } else {
                    commitEdit();
                    deselect();
                }
            }
        } else if (mouseY < lTop || mouseY >= lBot) {
            if (!searchField.isFocused() && !histSizeField.isFocused()) {
                commitEdit();
                deselect();
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // ESC
            mc.displayGuiScreen(null);
            return;
        }

        if (histSizeField.isFocused()) {
            if (keyCode == 28 || keyCode == 156) { // Enter
                applyHistSize();
                histSizeField.setFocused(false);
                searchField.setFocused(true);
            } else {
                histSizeField.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }

        if (searchField.isFocused()) {
            String before = searchField.getText();
            searchField.textboxKeyTyped(typedChar, keyCode);
            String after = searchField.getText();
            if (!after.equals(before)) applyFilter(after);
            return;
        }

        if (selectedRow >= 0 && valueField.isFocused()) {
            if (keyCode == 28 || keyCode == 156) { // Enter
                commitEdit();
                deselect();
            } else if (keyCode == 15) { // Tab → next entry
                commitEdit();
                advanceSelection(+1);
            } else {
                valueField.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dw = Mouse.getEventDWheel();
        if (dw != 0) {
            scrollOffset -= Integer.signum(dw) * 3;
            clampScroll();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1: { // Save
                commitEdit();
                Map<String, String> out = new LinkedHashMap<>();
                for (ConfigEntry e : allEntries) out.put(e.path, e.currentValue);
                MPBNetworkHandler.CHANNEL.sendToServer(new PacketSaveConfig(out));
                mc.displayGuiScreen(null);
                break;
            }
            case 2: // Cancel
                mc.displayGuiScreen(null);
                break;
            case 3: { // Sort toggle
                MPBGuiState.sortByModification = !MPBGuiState.sortByModification;
                button.displayString = MPBGuiState.sortByModification ? "§eSorted" : "Sort";
                rebuildDisplayRows();
                scrollOffset = 0;
                clampScroll();
                break;
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
