package org.fentanylsolutions.minemoticon.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.fentlib.util.FileUtil;
import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiPackLoader;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.api.FileTexture;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class PackManagementScreen extends GuiScreen {

    private static final int BTN_NEW_PACK = 99;
    private static final int BTN_OPEN_FOLDER = 100;
    private static final int BTN_RELOAD = 101;
    private static final int BTN_BACK = 102;

    private static final int PANEL_MARGIN = 18;
    private static final int HEADER_TITLE_Y = 12;
    private static final int HEADER_BOTTOM_PAD = 22;
    private static final int LIST_BOTTOM = 34;
    private static final int LIST_SCROLLBAR_W = 4;
    private static final int CARD_GAP = 8;
    private static final int CARD_HEIGHT = 128;
    private static final int CARD_PAD = 6;
    private static final int FIELD_H = 14;
    private static final int MINI_BTN_W = 16;
    private static final int SAVE_BTN_W = 36;
    private static final int ICON_PREVIEW_W = 12;
    private static final int EMOJI_BOX_RIGHT_GUTTER = 28;
    private static final int EMOJI_CELL = 12;
    private static final int EMOJI_GRID_ROWS = 3;
    private static final int LIST_SCROLL_STEP = 20;
    private static final float LIST_SCROLL_LERP = 0.35F;
    private static final int STATUS_DURATION_MS = 3500;
    private static final String DEFAULT_NEW_PACK_DISPLAY_NAME = "New Pack";
    private static final String[] SUPPORTED_EXTENSIONS = { "png", "jpg", "jpeg", "gif", "qoi", "webp" };
    private static final String EMPTY_RENAME_HINT = "Click an emoji above to rename. Shift-click to delete.";

    private final GuiScreen parent;
    private final List<PackCard> packCards = new ArrayList<>();

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int listScrollbarX;
    private int maxListScroll;
    private int listScroll;
    private float listScrollVisual;
    private boolean draggingListScrollbar;
    private int listScrollbarDragOffset;
    private PackCard draggingEmojiScrollbarCard;
    private int emojiScrollbarDragOffset;

    private String tooltipText;
    private int tooltipX;
    private int tooltipY;
    private String statusText;
    private int statusColor = 0xFFB0B0B0;
    private long statusUntilMs;
    private String pendingNewPackName;

    public PackManagementScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        listX = PANEL_MARGIN;
        listY = HEADER_TITLE_Y + fontRendererObj.FONT_HEIGHT + HEADER_BOTTOM_PAD;
        listW = width - PANEL_MARGIN * 2 - LIST_SCROLLBAR_W - 4;
        listH = height - listY - LIST_BOTTOM;
        listScrollbarX = listX + listW + 4;

        int bottomY = height - 24;
        int gap = 4;
        int btnW = Math.min(118, (width - PANEL_MARGIN * 2 - gap * 3) / 4);
        int totalW = btnW * 4 + gap * 3;
        int startX = (width - totalW) / 2;
        buttonList.add(new GuiButton(BTN_NEW_PACK, startX, bottomY, btnW, 20, "+ New Pack"));
        buttonList
            .add(new GuiButton(BTN_OPEN_FOLDER, startX + btnW + gap, bottomY, btnW, 20, "\uD83D\uDCC2 Packs Folder"));
        buttonList.add(new GuiButton(BTN_RELOAD, startX + (btnW + gap) * 2, bottomY, btnW, 20, "\uD83D\uDD04 Reload"));
        buttonList.add(new GuiButton(BTN_BACK, startX + (btnW + gap) * 3, bottomY, btnW, 20, "Back"));

        reloadPackCards(null, null);
        if (pendingNewPackName != null) {
            String requestedName = pendingNewPackName;
            pendingNewPackName = null;
            createNewPack(requestedName);
        }
        snapListScroll();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (Math.abs(listScrollVisual - listScroll) < 0.5F) {
            listScrollVisual = listScroll;
        } else {
            listScrollVisual += (listScroll - listScrollVisual) * LIST_SCROLL_LERP;
        }
        for (PackCard card : packCards) {
            card.tick();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        tooltipText = null;

        drawCenteredString(fontRendererObj, "Manage Client Emoji Packs", width / 2, HEADER_TITLE_Y, 0xFFFFFF);

        drawListBackground();
        renderPackCards(mouseX, mouseY);
        renderListScrollbar(mouseX, mouseY);
        renderStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (tooltipText != null) {
            renderTooltip(tooltipX, tooltipY, tooltipText);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_NEW_PACK) {
            mc.displayGuiScreen(new NewPackPromptScreen(this));
            return;
        }
        if (button.id == BTN_BACK) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == BTN_OPEN_FOLDER) {
            FileUtil.openFolder(EmojiPackLoader.getPacksFolder());
            return;
        }
        if (button.id == BTN_RELOAD) {
            reloadClientPacks(null, null, "Reloaded client packs", 0xFF80FF80);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            if (isInsideListScrollbar(mouseX, mouseY)) {
                beginListScrollbarDrag(mouseY);
                return;
            }

            if (!isInsideListViewport(mouseX, mouseY)) {
                super.mouseClicked(mouseX, mouseY, button);
                return;
            }

            for (PackCard card : packCards) {
                if (!card.updateLayout(listX, card.computeY(listY, getRenderedListScroll()), listW, listH)) {
                    card.blur();
                }
            }

            PackCard clickedCard = findVisibleCard(mouseX, mouseY);
            if (clickedCard != null && clickedCard.isInsideEmojiScrollbar(mouseX, mouseY)) {
                beginEmojiScrollbarDrag(clickedCard, mouseY);
                return;
            }

            PackEmojiItem clickedEmoji = clickedCard != null ? clickedCard.findEmojiAt(mouseX, mouseY) : null;
            boolean keepSelection = clickedCard != null
                && (clickedEmoji != null || clickedCard.isInsideRenameField(mouseX, mouseY)
                    || (clickedCard.isRenameSaveVisible() && clickedCard.isInsideRenameSaveButton(mouseX, mouseY)));

            for (PackCard card : packCards) {
                if (card != clickedCard || !keepSelection) {
                    card.clearSelectedEmoji();
                }
            }

            for (PackCard card : packCards) {
                if (card.isVisible()) {
                    card.mouseClicked(mouseX, mouseY, button);
                }
            }

            if (clickedCard != null) {
                if (handleCardClick(clickedCard, mouseX, mouseY)) {
                    return;
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0) {
            if (draggingListScrollbar) {
                updateListScrollbarDrag(mouseY);
                return;
            }
            if (draggingEmojiScrollbarCard != null) {
                draggingEmojiScrollbarCard.updateEmojiScrollbarDrag(mouseY, emojiScrollbarDragOffset);
                return;
            }
        }

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) {
            draggingListScrollbar = false;
            draggingEmojiScrollbarCard = null;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            mc.displayGuiScreen(parent);
            return;
        }

        if (keyCode == 15) {
            if (focusNextField(GuiScreen.isShiftKeyDown())) {
                return;
            }
        }

        if (keyCode == 28 || keyCode == 156) {
            if (saveFocusedField()) {
                return;
            }
        }

        for (PackCard card : packCards) {
            if (card.keyTyped(typedChar, keyCode)) {
                return;
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        int delta = Mouse.getEventDWheel();
        if (delta == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int steps = delta > 0 ? 1 : -1;

        PackCard hoveredCard = findVisibleCard(mouseX, mouseY);
        if (hoveredCard != null && hoveredCard.isInsideEmojiBox(mouseX, mouseY)) {
            hoveredCard.scrollEmojiGrid(-steps);
            return;
        }

        if (mouseX >= listX && mouseX <= listX + listW + LIST_SCROLLBAR_W + 6
            && mouseY >= listY
            && mouseY <= listY + listH) {
            listScroll = clamp(listScroll - steps * LIST_SCROLL_STEP, 0, maxListScroll);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawListBackground() {
        Gui.drawRect(listX - 2, listY - 2, listX + listW + LIST_SCROLLBAR_W + 6, listY + listH + 2, 0x80202020);
        Gui.drawRect(listX - 1, listY - 1, listX + listW + LIST_SCROLLBAR_W + 5, listY + listH + 1, 0xA0000000);
    }

    private void renderPackCards(int mouseX, int mouseY) {
        enableScissor(listX, listY, listW + 1, listH);

        if (packCards.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                "No client packs found. Click New Pack or drop some emoji files in config/minemoticon/packs.",
                width / 2,
                listY + listH / 2 - 4,
                0xA8A8A8);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }

        for (PackCard card : packCards) {
            int cardY = card.computeY(listY, getRenderedListScroll());
            if (!card.updateLayout(listX, cardY, listW, listH)) {
                continue;
            }
            card.draw(mouseX, mouseY);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderListScrollbar(int mouseX, int mouseY) {
        if (maxListScroll <= 0) {
            return;
        }

        Gui.drawRect(listScrollbarX, listY, listScrollbarX + LIST_SCROLLBAR_W, listY + listH, 0x30FFFFFF);
        float viewRatio = (float) listH / (float) (listH + maxListScroll);
        int thumbH = Math.max(12, (int) (listH * viewRatio));
        int travel = listH - thumbH;
        int thumbY = listY + (travel <= 0 ? 0 : (int) ((float) getRenderedListScroll() / maxListScroll * travel));
        boolean hovered = mouseX >= listScrollbarX - 2 && mouseX <= listScrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
        Gui.drawRect(
            listScrollbarX,
            thumbY,
            listScrollbarX + LIST_SCROLLBAR_W,
            thumbY + thumbH,
            hovered ? 0xA0FFFFFF : 0x70FFFFFF);
    }

    private void renderStatus() {
        if (statusText == null || System.currentTimeMillis() > statusUntilMs) {
            return;
        }
        drawCenteredString(fontRendererObj, statusText, width / 2, height - 36, statusColor);
    }

    private void renderTooltip(int mouseX, int mouseY, String text) {
        int textWidth = fontRendererObj.getStringWidth(text);
        int tipX = mouseX + 12;
        int tipY = mouseY - 6;
        if (tipX + textWidth + 6 > width) {
            tipX = mouseX - textWidth - 8;
        }
        Gui.drawRect(tipX - 3, tipY - 3, tipX + textWidth + 3, tipY + 11, 0xE0000000);
        Gui.drawRect(tipX - 3, tipY - 3, tipX + textWidth + 3, tipY - 2, 0x50FF8080);
        fontRendererObj.drawStringWithShadow(text, tipX, tipY, 0xFFFFFF);
    }

    private void enableScissor(int x, int y, int w, int h) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, (height - y - h) * scale, w * scale, h * scale);
    }

    private void enableIntersectedScissor(int x, int y, int w, int h, int clipX, int clipY, int clipW, int clipH) {
        int left = Math.max(x, clipX);
        int top = Math.max(y, clipY);
        int right = Math.min(x + w, clipX + clipW);
        int bottom = Math.min(y + h, clipY + clipH);

        if (right <= left || bottom <= top) {
            enableScissor(0, 0, 0, 0);
            return;
        }

        enableScissor(left, top, right - left, bottom - top);
    }

    private PackCard findVisibleCard(int mouseX, int mouseY) {
        if (!isInsideListViewport(mouseX, mouseY)) {
            return null;
        }
        for (PackCard card : packCards) {
            if (card.isVisible() && card.contains(mouseX, mouseY)) {
                return card;
            }
        }
        return null;
    }

    private boolean isInsideListViewport(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW + LIST_SCROLLBAR_W + 6
            && mouseY >= listY
            && mouseY < listY + listH;
    }

    private boolean isInsideListScrollbar(int mouseX, int mouseY) {
        return maxListScroll > 0 && mouseX >= listScrollbarX - 2
            && mouseX <= listScrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
    }

    private void beginListScrollbarDrag(int mouseY) {
        draggingListScrollbar = true;
        int thumbY = getListScrollbarThumbY();
        int thumbH = getListScrollbarThumbHeight();
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            listScrollbarDragOffset = mouseY - thumbY;
        } else {
            listScrollbarDragOffset = thumbH / 2;
        }
        updateListScrollbarDrag(mouseY);
    }

    private void updateListScrollbarDrag(int mouseY) {
        int thumbH = getListScrollbarThumbHeight();
        int travel = listH - thumbH;
        if (travel <= 0 || maxListScroll <= 0) {
            listScroll = 0;
            snapListScroll();
            return;
        }

        int thumbY = clamp(mouseY - listScrollbarDragOffset, listY, listY + travel);
        float ratio = (float) (thumbY - listY) / (float) travel;
        listScroll = clamp(Math.round(ratio * maxListScroll), 0, maxListScroll);
        snapListScroll();
    }

    private int getListScrollbarThumbHeight() {
        if (maxListScroll <= 0) {
            return listH;
        }
        float viewRatio = (float) listH / (float) (listH + maxListScroll);
        return Math.max(12, (int) (listH * viewRatio));
    }

    private int getListScrollbarThumbY() {
        int thumbH = getListScrollbarThumbHeight();
        int travel = listH - thumbH;
        return listY + (travel <= 0 ? 0 : (int) ((float) getRenderedListScroll() / maxListScroll * travel));
    }

    private void beginEmojiScrollbarDrag(PackCard card, int mouseY) {
        draggingEmojiScrollbarCard = card;
        int thumbY = card.getEmojiScrollbarThumbY();
        int thumbH = card.getEmojiScrollbarThumbHeight();
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            emojiScrollbarDragOffset = mouseY - thumbY;
        } else {
            emojiScrollbarDragOffset = thumbH / 2;
        }
        card.updateEmojiScrollbarDrag(mouseY, emojiScrollbarDragOffset);
    }

    private boolean handleCardClick(PackCard card, int mouseX, int mouseY) {
        if (card.isInsideOpenFolderButton(mouseX, mouseY)) {
            FileUtil.openFolder(card.folder);
            return true;
        }
        if (card.isInsideAddButton(mouseX, mouseY)) {
            addEmojiToPack(card);
            return true;
        }
        if (card.isMetaSaveVisible() && card.isInsideMetaSaveButton(mouseX, mouseY)) {
            savePackMeta(card);
            return true;
        }
        if (card.isRenameSaveVisible() && card.isInsideRenameSaveButton(mouseX, mouseY)) {
            saveSelectedEmoji(card);
            return true;
        }

        PackEmojiItem clickedEmoji = card.findEmojiAt(mouseX, mouseY);
        if (clickedEmoji != null) {
            if (GuiScreen.isShiftKeyDown()) {
                deleteEmoji(card, clickedEmoji);
            } else {
                card.selectEmoji(clickedEmoji);
            }
            return true;
        }

        return false;
    }

    private boolean saveFocusedField() {
        for (PackCard card : packCards) {
            if (card.isMetaFieldFocused()) {
                if (card.isMetaDirty()) {
                    savePackMeta(card);
                }
                return true;
            }
            if (card.isRenameFieldFocused()) {
                if (card.isRenameDirty()) {
                    saveSelectedEmoji(card);
                }
                return true;
            }
        }
        return false;
    }

    private boolean focusNextField(boolean backwards) {
        List<FocusableField> fields = new ArrayList<>();
        for (PackCard card : packCards) {
            fields.add(new FocusableField(card, FocusField.DISPLAY_NAME));
            fields.add(new FocusableField(card, FocusField.ICON_NAME));
            if (card.hasSelectedEmoji()) {
                fields.add(new FocusableField(card, FocusField.EMOJI_NAME));
            }
        }
        if (fields.isEmpty()) {
            return false;
        }

        int currentIndex = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i)
                .isFocused()) {
                currentIndex = i;
                break;
            }
        }

        int targetIndex;
        if (currentIndex < 0) {
            targetIndex = backwards ? fields.size() - 1 : 0;
        } else {
            int delta = backwards ? -1 : 1;
            targetIndex = (currentIndex + delta + fields.size()) % fields.size();
        }

        for (PackCard card : packCards) {
            card.blur();
        }

        FocusableField target = fields.get(targetIndex);
        ensureCardVisible(target.card);
        refreshCardLayouts();
        target.focus();
        return true;
    }

    private void savePackMeta(PackCard card) {
        try {
            EmojiPackLoader.writePackMeta(card.folder, card.getDisplayNameText(), card.getIconNameText());
            reloadClientPacks(
                card.folder.getAbsolutePath(),
                card.getSelectedEmojiName(),
                "Saved " + card.getCardTitle(),
                0xFF80FF80);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to save pack meta for {}", card.folder.getName(), e);
            showStatus("Failed to save " + card.getCardTitle(), 0xFFFF8080);
        }
    }

    private void saveSelectedEmoji(PackCard card) {
        PackEmojiItem selected = card.selectedEmoji;
        if (selected == null) {
            return;
        }

        String requestedName = sanitizeEmojiName(card.getSelectedEmojiText());
        if (requestedName.isEmpty()) {
            showStatus("Emoji name cannot be empty", 0xFFFF8080);
            return;
        }

        File target = uniqueEmojiFile(card.folder, requestedName, selected.extension, selected.file);
        try {
            if (!selected.file.equals(target)) {
                Files.move(selected.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String iconName = card.getIconNameText();
            if (iconName.equals(selected.name)) {
                EmojiPackLoader.writePackMeta(card.folder, card.getDisplayNameText(), requestedName);
            } else if (card.isMetaDirty()) {
                EmojiPackLoader.writePackMeta(card.folder, card.getDisplayNameText(), iconName);
            }

            reloadClientPacks(
                card.folder.getAbsolutePath(),
                requestedName,
                "Renamed emoji to " + requestedName,
                0xFF80FF80);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to rename emoji {} in {}", selected.name, card.folder.getName(), e);
            showStatus("Failed to rename emoji", 0xFFFF8080);
        }
    }

    private void deleteEmoji(PackCard card, PackEmojiItem emoji) {
        try {
            Files.deleteIfExists(emoji.file.toPath());
            String iconName = card.getIconNameText();
            if (iconName.equals(emoji.name)) {
                EmojiPackLoader.writePackMeta(card.folder, card.getDisplayNameText(), "");
            } else if (card.isMetaDirty()) {
                EmojiPackLoader.writePackMeta(card.folder, card.getDisplayNameText(), iconName);
            }

            reloadClientPacks(card.folder.getAbsolutePath(), null, "Deleted " + emoji.name, 0xFF80FF80);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to delete emoji {} from {}", emoji.name, card.folder.getName(), e);
            showStatus("Failed to delete emoji", 0xFFFF8080);
        }
    }

    private void addEmojiToPack(PackCard card) {
        FileUtil.FilePickerResult result = FileUtil.pickFile("Select emoji file", card.folder, SUPPORTED_EXTENSIONS);

        switch (result.getStatus()) {
            case CANCELLED:
                return;
            case UNAVAILABLE:
            case ERROR:
                showStatus(result.getMessage(), 0xFFFF8080);
                return;
            case SELECTED:
                break;
        }

        File source = result.getFile();
        if (source == null || !EmojiPackLoader.isSupportedEmojiFile(source)) {
            showStatus("Selected file is not a supported emoji image", 0xFFFF8080);
            return;
        }

        String extension = getExtension(source.getName());
        String baseName = sanitizeEmojiName(stripExtension(source.getName()));
        if (baseName.isEmpty()) {
            baseName = "emoji";
        }

        File target = uniqueEmojiFile(card.folder, baseName, extension, null);
        try {
            Files.copy(source.toPath(), target.toPath());
            reloadClientPacks(
                card.folder.getAbsolutePath(),
                stripExtension(target.getName()),
                "Added " + stripExtension(target.getName()),
                0xFF80FF80);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to add emoji {} to {}", source, card.folder.getName(), e);
            showStatus("Failed to add emoji", 0xFFFF8080);
        }
    }

    private void createNewPack(String requestedDisplayName) {
        File root = EmojiPackLoader.getPacksFolder();
        FileUtil.createFolderIfNotExists(root);

        String displayName = requestedDisplayName != null ? requestedDisplayName.trim() : "";
        if (displayName.isEmpty()) {
            displayName = DEFAULT_NEW_PACK_DISPLAY_NAME;
        }

        File newFolder = uniquePackFolder(root, sanitizePackFolderName(displayName));
        try {
            Files.createDirectories(newFolder.toPath());
            EmojiPackLoader.writePackMeta(newFolder, displayName, "");
            reloadPackCards(newFolder.getAbsolutePath(), null);
            ensureCardVisible(findCardByFolderPath(newFolder.getAbsolutePath()));
            refreshCardLayouts();
            focusField(newFolder.getAbsolutePath(), FocusField.DISPLAY_NAME);
            showStatus("Created " + displayName, 0xFF80FF80);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to create pack {}", newFolder, e);
            showStatus("Failed to create pack", 0xFFFF8080);
        }
    }

    private void queueNewPack(String requestedName) {
        pendingNewPackName = requestedName;
    }

    private void reloadClientPacks(String selectedFolderPath, String selectedEmojiName, String status, int color) {
        ClientEmojiHandler.reloadPacks();
        reloadPackCards(selectedFolderPath, selectedEmojiName);
        showStatus(status, color);
    }

    private void reloadPackCards(String selectedFolderPath, String selectedEmojiName) {
        destroyCards();
        packCards.clear();

        List<File> folders = EmojiPackLoader.listPackFolders(EmojiPackLoader.getPacksFolder());
        for (File folder : folders) {
            EmojiPackLoader.PackMeta meta = EmojiPackLoader.readPackMeta(folder);
            File[] files = folder.listFiles((dir, name) -> EmojiPackLoader.isSupportedEmojiFileName(name));
            if (files == null) {
                files = new File[0];
            }
            Arrays.sort(files);

            PackCard card = new PackCard(folder, meta, fontRendererObj);
            for (File file : files) {
                String emojiName = stripExtension(file.getName());
                PackPreviewEmoji preview = new PackPreviewEmoji(file, meta.folderName, emojiName);
                card.emojis.add(new PackEmojiItem(file, emojiName, getExtension(file.getName()), preview));
            }

            if (selectedFolderPath != null && selectedFolderPath.equals(folder.getAbsolutePath())
                && selectedEmojiName != null) {
                PackEmojiItem selectedEmoji = card.findEmojiByName(selectedEmojiName);
                if (selectedEmoji != null) {
                    card.selectEmoji(selectedEmoji);
                }
            }

            packCards.add(card);
        }

        maxListScroll = Math.max(0, packCards.size() * (CARD_HEIGHT + CARD_GAP) - CARD_GAP - listH);
        listScroll = clamp(listScroll, 0, maxListScroll);
        listScrollVisual = Math.max(0.0F, Math.min(maxListScroll, listScrollVisual));
    }

    private PackCard findCardByFolderPath(String folderPath) {
        if (folderPath == null) {
            return null;
        }
        for (PackCard card : packCards) {
            if (folderPath.equals(card.folder.getAbsolutePath())) {
                return card;
            }
        }
        return null;
    }

    private void ensureCardVisible(PackCard card) {
        if (card == null) {
            return;
        }

        int index = packCards.indexOf(card);
        if (index < 0) {
            return;
        }

        int rawTop = index * (CARD_HEIGHT + CARD_GAP);
        int rawBottom = rawTop + CARD_HEIGHT;
        if (rawTop < listScroll) {
            listScroll = rawTop;
        } else if (rawBottom > listScroll + listH) {
            listScroll = rawBottom - listH;
        }
        listScroll = clamp(listScroll, 0, maxListScroll);
        snapListScroll();
    }

    private void refreshCardLayouts() {
        for (PackCard card : packCards) {
            card.updateLayout(listX, card.computeY(listY, getRenderedListScroll()), listW, listH);
        }
    }

    private void focusField(String folderPath, FocusField field) {
        PackCard card = findCardByFolderPath(folderPath);
        if (card == null) {
            return;
        }
        for (PackCard other : packCards) {
            if (other != card) {
                other.blur();
            }
        }
        card.focusField(field);
    }

    private void destroyCards() {
        for (PackCard card : packCards) {
            card.destroy();
        }
    }

    private void showStatus(String text, int color) {
        statusText = text;
        statusColor = color;
        statusUntilMs = System.currentTimeMillis() + STATUS_DURATION_MS;
    }

    private static File uniqueEmojiFile(File folder, String baseName, String extension, File existingFile) {
        String normalizedBase = sanitizeEmojiName(baseName);
        if (normalizedBase.isEmpty()) {
            normalizedBase = "emoji";
        }

        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        File candidate = new File(folder, normalizedBase + normalizedExtension);
        if (existingFile != null && existingFile.equals(candidate)) {
            return candidate;
        }

        int index = 2;
        while (candidate.exists()) {
            candidate = new File(folder, normalizedBase + "_" + index + normalizedExtension);
            if (existingFile != null && existingFile.equals(candidate)) {
                return candidate;
            }
            index++;
        }

        return candidate;
    }

    private static String sanitizeEmojiName(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return sanitized;
    }

    private static String stripExtension(String filename) {
        return filename.replaceFirst("\\.[^.]+$", "");
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return filename.substring(dot)
            .toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getRenderedListScroll() {
        return Math.round(listScrollVisual);
    }

    private void snapListScroll() {
        listScrollVisual = listScroll;
    }

    private static File uniquePackFolder(File root, String baseName) {
        File candidate = new File(root, baseName);
        int index = 2;
        while (candidate.exists()) {
            candidate = new File(root, baseName + "_" + index);
            index++;
        }
        return candidate;
    }

    private static String sanitizePackFolderName(String raw) {
        String sanitized = sanitizeEmojiName(raw);
        return sanitized.isEmpty() ? "pack" : sanitized;
    }

    private enum FocusField {
        DISPLAY_NAME,
        ICON_NAME,
        EMOJI_NAME
    }

    private final class PackCard {

        private final File folder;
        private final String folderLabel;
        private final String originalDisplayName;
        private final String originalIconName;
        private final GuiTextField displayNameField;
        private final GuiTextField iconField;
        private final GuiTextField selectedEmojiField;
        private final List<PackEmojiItem> emojis = new ArrayList<>();

        private int emojiScrollRows;
        private PackEmojiItem selectedEmoji;

        private boolean visible;
        private int x;
        private int y;
        private int width;
        private int nameFieldX;
        private int nameFieldY;
        private int nameFieldW;
        private int iconFieldX;
        private int iconFieldY;
        private int iconFieldW;
        private int iconPreviewX;
        private int metaSaveX;
        private int openFolderButtonX;
        private int addButtonX;
        private int buttonY;
        private int emojiBoxX;
        private int emojiBoxY;
        private int emojiBoxW;
        private int emojiBoxH;
        private int renamePreviewX;
        private int renameFieldX;
        private int renameFieldY;
        private int renameFieldW;
        private int renameSaveX;

        PackCard(File folder, EmojiPackLoader.PackMeta meta, FontRenderer font) {
            this.folder = folder;
            this.folderLabel = folder.getName();
            this.originalDisplayName = meta.displayName;
            this.originalIconName = meta.iconEmojiName != null ? meta.iconEmojiName : "";

            this.displayNameField = new GuiTextField(font, 0, 0, 10, FIELD_H);
            this.displayNameField.setMaxStringLength(64);
            this.displayNameField.setText(meta.displayName);
            this.displayNameField.setCursorPositionZero();

            this.iconField = new GuiTextField(font, 0, 0, 10, FIELD_H);
            this.iconField.setMaxStringLength(48);
            this.iconField.setText(this.originalIconName);
            this.iconField.setCursorPositionZero();

            this.selectedEmojiField = new GuiTextField(font, 0, 0, 10, FIELD_H);
            this.selectedEmojiField.setMaxStringLength(48);
        }

        int computeY(int baseY, int scroll) {
            return baseY - scroll + packCards.indexOf(this) * (CARD_HEIGHT + CARD_GAP);
        }

        boolean updateLayout(int x, int y, int width, int viewportHeight) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.visible = y + CARD_HEIGHT >= listY && y <= listY + viewportHeight;

            if (!visible) {
                blur();
                return false;
            }

            this.openFolderButtonX = x + width - CARD_PAD - MINI_BTN_W;
            this.addButtonX = openFolderButtonX - 4 - MINI_BTN_W;
            this.metaSaveX = addButtonX - 6 - SAVE_BTN_W;
            this.iconFieldW = Math.min(88, Math.max(56, width / 7));
            this.iconPreviewX = metaSaveX - 4 - ICON_PREVIEW_W;
            this.nameFieldX = x + CARD_PAD;
            this.iconFieldX = iconPreviewX - 6 - iconFieldW;
            this.nameFieldW = Math.max(90, iconFieldX - 8 - nameFieldX);
            this.nameFieldY = y + 32;
            this.iconFieldY = nameFieldY;
            this.buttonY = nameFieldY;

            this.emojiBoxX = x + CARD_PAD;
            this.emojiBoxY = y + 54;
            this.emojiBoxW = width - CARD_PAD * 2 - EMOJI_BOX_RIGHT_GUTTER;
            this.emojiBoxH = EMOJI_GRID_ROWS * EMOJI_CELL + 6;

            this.renamePreviewX = x + CARD_PAD + fontRendererObj.getStringWidth("Emoji") + 6;
            this.renameFieldX = renamePreviewX + EMOJI_CELL + 8;
            this.renameFieldY = emojiBoxY + emojiBoxH + 10;
            this.renameFieldW = width - CARD_PAD - SAVE_BTN_W - 6 - renameFieldX;
            this.renameSaveX = renameFieldX + renameFieldW + 6;

            displayNameField.xPosition = nameFieldX;
            displayNameField.yPosition = nameFieldY;
            displayNameField.width = nameFieldW;
            displayNameField.height = FIELD_H;

            iconField.xPosition = iconFieldX;
            iconField.yPosition = iconFieldY;
            iconField.width = iconFieldW;
            iconField.height = FIELD_H;

            selectedEmojiField.xPosition = renameFieldX;
            selectedEmojiField.yPosition = renameFieldY;
            selectedEmojiField.width = renameFieldW;
            selectedEmojiField.height = FIELD_H;
            selectedEmojiField.setEnabled(selectedEmoji != null);

            return true;
        }

        void tick() {
            displayNameField.updateCursorCounter();
            iconField.updateCursorCounter();
            if (selectedEmoji != null) {
                selectedEmojiField.updateCursorCounter();
            }
        }

        void draw(int mouseX, int mouseY) {
            Gui.drawRect(x, y, x + width, y + CARD_HEIGHT, 0x70000000);
            Gui.drawRect(x, y, x + width, y + 1, 0x30FFFFFF);
            Gui.drawRect(x, y + CARD_HEIGHT - 1, x + width, y + CARD_HEIGHT, 0x20000000);

            fontRendererObj.drawStringWithShadow(
                trimToWidthWithEllipsis(getCardTitle(), width - CARD_PAD * 2 - MINI_BTN_W * 2 - 8),
                x + CARD_PAD,
                y + 6,
                0xD8D8D8);

            drawFieldLabel("Pack", nameFieldX, y + 22);
            drawFieldLabel("Icon", iconFieldX, y + 22, isIconNameInvalid() ? 0xFFB86A6A : 0x909090);
            displayNameField.drawTextBox();
            iconField.drawTextBox();

            drawButton(
                openFolderButtonX,
                buttonY,
                MINI_BTN_W,
                FIELD_H,
                "\u2197",
                isInsideOpenFolderButton(mouseX, mouseY),
                0xFFB58C4D);
            if (isInsideOpenFolderButton(mouseX, mouseY)) {
                setTooltip(mouseX, mouseY, "Open folder");
            }

            drawButton(addButtonX, buttonY, MINI_BTN_W, FIELD_H, "+", isInsideAddButton(mouseX, mouseY), 0xFF5EA35E);
            if (isInsideAddButton(mouseX, mouseY)) {
                setTooltip(mouseX, mouseY, "Add emoji");
            }

            if (isMetaSaveVisible()) {
                drawButton(
                    metaSaveX,
                    buttonY,
                    SAVE_BTN_W,
                    FIELD_H,
                    "Save",
                    isInsideMetaSaveButton(mouseX, mouseY),
                    0xFF4A7AB7);
            }

            drawEmojiBox(mouseX, mouseY);
            drawRenameRow(mouseX, mouseY);
        }

        private void drawEmojiBox(int mouseX, int mouseY) {
            Gui.drawRect(emojiBoxX, emojiBoxY, emojiBoxX + emojiBoxW, emojiBoxY + emojiBoxH, 0xB0101010);
            Gui.drawRect(emojiBoxX, emojiBoxY, emojiBoxX + emojiBoxW, emojiBoxY + 1, 0x20FFFFFF);

            int cols = getEmojiCols();
            int totalRows = getEmojiRowCount(cols);
            int maxScrollRows = Math.max(0, totalRows - EMOJI_GRID_ROWS);
            emojiScrollRows = clamp(emojiScrollRows, 0, maxScrollRows);

            enableIntersectedScissor(emojiBoxX, emojiBoxY, emojiBoxW, emojiBoxH, listX, listY, listW + 1, listH);

            for (int index = 0; index < emojis.size(); index++) {
                int row = index / cols;
                int col = index % cols;
                int drawRow = row - emojiScrollRows;
                if (drawRow < 0 || drawRow >= EMOJI_GRID_ROWS) {
                    continue;
                }

                int cellX = emojiBoxX + 3 + col * EMOJI_CELL;
                int cellY = emojiBoxY + 3 + drawRow * EMOJI_CELL;
                PackEmojiItem emoji = emojis.get(index);
                boolean hovered = mouseX >= cellX && mouseX < cellX + EMOJI_CELL
                    && mouseY >= cellY
                    && mouseY < cellY + EMOJI_CELL;
                boolean selected = emoji == selectedEmoji;
                boolean deleteHover = hovered && GuiScreen.isShiftKeyDown();

                EmojiRenderer.renderQuad(emoji.preview, cellX + 1, cellY + 1);

                if (!hovered && !selected) {
                    Gui.drawRect(cellX, cellY, cellX + EMOJI_CELL, cellY + EMOJI_CELL, 0x70000000);
                }
                if (selected) {
                    Gui.drawRect(cellX, cellY, cellX + EMOJI_CELL, cellY + EMOJI_CELL, 0x28FFFFFF);
                    drawOutline(cellX, cellY, EMOJI_CELL, EMOJI_CELL, 0x60FFFFFF);
                } else if (hovered) {
                    drawOutline(cellX, cellY, EMOJI_CELL, EMOJI_CELL, 0x40FFFFFF);
                }
                if (deleteHover) {
                    Gui.drawRect(cellX, cellY, cellX + EMOJI_CELL, cellY + EMOJI_CELL, 0x90AA2020);
                    setTooltip(mouseX, mouseY, "Delete");
                } else if (hovered) {
                    setTooltip(mouseX, mouseY, emoji.name);
                }
            }

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            enableScissor(listX, listY, listW + 1, listH);

            if (maxScrollRows > 0) {
                int scrollbarX = emojiBoxX + emojiBoxW - 3;
                Gui.drawRect(scrollbarX, emojiBoxY + 1, scrollbarX + 2, emojiBoxY + emojiBoxH - 1, 0x30FFFFFF);
                float viewRatio = (float) EMOJI_GRID_ROWS / totalRows;
                int thumbH = Math.max(8, (int) ((emojiBoxH - 2) * viewRatio));
                int travel = emojiBoxH - 2 - thumbH;
                int thumbY = emojiBoxY + 1
                    + (travel <= 0 ? 0 : (int) ((float) emojiScrollRows / maxScrollRows * travel));
                Gui.drawRect(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbH, 0x70FFFFFF);
            }
        }

        private void drawRenameRow(int mouseX, int mouseY) {
            drawFieldLabel("Emoji", x + CARD_PAD, renameFieldY + 3);

            if (selectedEmoji != null) {
                EmojiRenderer.renderQuad(selectedEmoji.preview, renamePreviewX, renameFieldY + 1);
            }

            if (selectedEmoji != null) {
                selectedEmojiField.drawTextBox();
                if (isRenameSaveVisible()) {
                    drawButton(
                        renameSaveX,
                        renameFieldY,
                        SAVE_BTN_W,
                        FIELD_H,
                        "Save",
                        isInsideRenameSaveButton(mouseX, mouseY),
                        0xFF4A7AB7);
                }
            } else {
                Gui.drawRect(
                    renameFieldX,
                    renameFieldY,
                    renameFieldX + renameFieldW,
                    renameFieldY + FIELD_H,
                    0x40101010);
                fontRendererObj.drawStringWithShadow(
                    trimToWidthWithEllipsis(EMPTY_RENAME_HINT, renameFieldW - 8),
                    renameFieldX + 4,
                    renameFieldY + 3,
                    0x777777);
            }

            PackEmojiItem iconEmoji = findEmojiByName(getIconNameText());
            if (iconEmoji != null) {
                EmojiRenderer.renderQuad(iconEmoji.preview, iconPreviewX, iconFieldY + 1);
            } else if (isIconNameInvalid()) {
                Gui.drawRect(
                    iconPreviewX,
                    iconFieldY + 1,
                    iconPreviewX + ICON_PREVIEW_W,
                    iconFieldY + FIELD_H - 1,
                    0x60302020);
                drawCenteredString(fontRendererObj, "!", iconPreviewX + ICON_PREVIEW_W / 2, iconFieldY + 3, 0xFFFF7A7A);
                if (isInside(mouseX, mouseY, iconFieldX, iconFieldY, iconFieldW, FIELD_H)
                    || isInside(mouseX, mouseY, iconPreviewX, iconFieldY, ICON_PREVIEW_W, FIELD_H)) {
                    setTooltip(mouseX, mouseY, "Icon not found in this pack");
                }
            }
        }

        void mouseClicked(int mouseX, int mouseY, int button) {
            displayNameField.mouseClicked(mouseX, mouseY, button);
            iconField.mouseClicked(mouseX, mouseY, button);
            if (selectedEmoji != null) {
                selectedEmojiField.mouseClicked(mouseX, mouseY, button);
            } else {
                selectedEmojiField.setFocused(false);
            }
        }

        boolean keyTyped(char typedChar, int keyCode) {
            if (displayNameField.textboxKeyTyped(typedChar, keyCode)) {
                return true;
            }
            if (iconField.textboxKeyTyped(typedChar, keyCode)) {
                return true;
            }
            return selectedEmoji != null && selectedEmojiField.textboxKeyTyped(typedChar, keyCode);
        }

        void blur() {
            displayNameField.setFocused(false);
            iconField.setFocused(false);
            selectedEmojiField.setFocused(false);
        }

        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CARD_HEIGHT;
        }

        boolean isVisible() {
            return visible;
        }

        boolean isInsideAddButton(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, addButtonX, buttonY, MINI_BTN_W, FIELD_H);
        }

        boolean isInsideOpenFolderButton(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, openFolderButtonX, buttonY, MINI_BTN_W, FIELD_H);
        }

        boolean isInsideMetaSaveButton(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, metaSaveX, buttonY, SAVE_BTN_W, FIELD_H);
        }

        boolean isInsideRenameSaveButton(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, renameSaveX, renameFieldY, SAVE_BTN_W, FIELD_H);
        }

        boolean isInsideRenameField(int mouseX, int mouseY) {
            return selectedEmoji != null && isInside(mouseX, mouseY, renameFieldX, renameFieldY, renameFieldW, FIELD_H);
        }

        boolean isInsideEmojiBox(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, emojiBoxX, emojiBoxY, emojiBoxW, emojiBoxH);
        }

        boolean isInsideEmojiScrollbar(int mouseX, int mouseY) {
            if (!hasEmojiScrollbar()) {
                return false;
            }
            int scrollbarX = emojiBoxX + emojiBoxW - 4;
            return isInside(mouseX, mouseY, scrollbarX, emojiBoxY, 6, emojiBoxH);
        }

        void scrollEmojiGrid(int amount) {
            int maxRows = Math.max(0, getEmojiRowCount(getEmojiCols()) - EMOJI_GRID_ROWS);
            emojiScrollRows = clamp(emojiScrollRows + amount, 0, maxRows);
        }

        void updateEmojiScrollbarDrag(int mouseY, int dragOffset) {
            int maxRows = getMaxEmojiScrollRows();
            int thumbH = getEmojiScrollbarThumbHeight();
            int travel = emojiBoxH - 2 - thumbH;
            if (maxRows <= 0 || travel <= 0) {
                emojiScrollRows = 0;
                return;
            }

            int thumbY = clamp(mouseY - dragOffset, emojiBoxY + 1, emojiBoxY + 1 + travel);
            float ratio = (float) (thumbY - (emojiBoxY + 1)) / (float) travel;
            emojiScrollRows = clamp(Math.round(ratio * maxRows), 0, maxRows);
        }

        PackEmojiItem findEmojiAt(int mouseX, int mouseY) {
            if (!isInsideEmojiBox(mouseX, mouseY)) {
                return null;
            }

            int cols = getEmojiCols();
            int relativeX = mouseX - emojiBoxX - 3;
            int relativeY = mouseY - emojiBoxY - 3;
            if (relativeX < 0 || relativeY < 0) {
                return null;
            }

            int col = relativeX / EMOJI_CELL;
            int row = relativeY / EMOJI_CELL;
            if (row < 0 || row >= EMOJI_GRID_ROWS || col < 0 || col >= cols) {
                return null;
            }

            int index = (row + emojiScrollRows) * cols + col;
            if (index < 0 || index >= emojis.size()) {
                return null;
            }

            return emojis.get(index);
        }

        void selectEmoji(PackEmojiItem emoji) {
            selectedEmoji = emoji;
            selectedEmojiField.setText(emoji.name);
            selectedEmojiField.setFocused(true);

            int cols = getEmojiCols();
            int row = emojis.indexOf(emoji) / cols;
            if (row < emojiScrollRows) {
                emojiScrollRows = row;
            } else if (row >= emojiScrollRows + EMOJI_GRID_ROWS) {
                emojiScrollRows = row - EMOJI_GRID_ROWS + 1;
            }
        }

        void clearSelectedEmoji() {
            selectedEmoji = null;
            selectedEmojiField.setText("");
            selectedEmojiField.setFocused(false);
        }

        boolean hasSelectedEmoji() {
            return selectedEmoji != null;
        }

        PackEmojiItem findEmojiByName(String name) {
            for (PackEmojiItem emoji : emojis) {
                if (emoji.name.equals(name)) {
                    return emoji;
                }
            }
            return null;
        }

        boolean isMetaDirty() {
            return !originalDisplayName.equals(getDisplayNameText()) || !originalIconName.equals(getIconNameText());
        }

        boolean isRenameDirty() {
            return selectedEmoji != null && !selectedEmoji.name.equals(getSelectedEmojiText());
        }

        boolean isMetaSaveVisible() {
            return isMetaDirty();
        }

        boolean isRenameSaveVisible() {
            return selectedEmoji != null && isRenameDirty();
        }

        boolean isMetaFieldFocused() {
            return displayNameField.isFocused() || iconField.isFocused();
        }

        boolean isRenameFieldFocused() {
            return selectedEmojiField.isFocused();
        }

        boolean isFieldFocused(FocusField field) {
            switch (field) {
                case DISPLAY_NAME:
                    return displayNameField.isFocused();
                case ICON_NAME:
                    return iconField.isFocused();
                case EMOJI_NAME:
                    return selectedEmoji != null && selectedEmojiField.isFocused();
                default:
                    return false;
            }
        }

        void focusField(FocusField field) {
            blur();
            switch (field) {
                case DISPLAY_NAME:
                    displayNameField.setFocused(true);
                    displayNameField.setCursorPositionEnd();
                    break;
                case ICON_NAME:
                    iconField.setFocused(true);
                    iconField.setCursorPositionEnd();
                    break;
                case EMOJI_NAME:
                    if (selectedEmoji != null) {
                        selectedEmojiField.setFocused(true);
                        selectedEmojiField.setCursorPositionEnd();
                    }
                    break;
            }
        }

        String getDisplayNameText() {
            return displayNameField.getText()
                .trim();
        }

        String getIconNameText() {
            return iconField.getText()
                .trim();
        }

        String getSelectedEmojiText() {
            return selectedEmojiField.getText()
                .trim();
        }

        String getSelectedEmojiName() {
            return selectedEmoji != null ? selectedEmoji.name : null;
        }

        String getFolderLabel() {
            return folderLabel;
        }

        String getCardTitle() {
            String displayName = getDisplayNameText();
            return displayName.isEmpty() ? folderLabel : displayName;
        }

        boolean isIconNameInvalid() {
            String iconName = getIconNameText();
            return !iconName.isEmpty() && findEmojiByName(iconName) == null;
        }

        void destroy() {
            for (PackEmojiItem emoji : emojis) {
                emoji.preview.destroy();
            }
        }

        private int getEmojiCols() {
            return Math.max(1, (emojiBoxW - 8) / EMOJI_CELL);
        }

        private int getEmojiRowCount(int cols) {
            return cols <= 0 ? 0 : (emojis.size() + cols - 1) / cols;
        }

        private int getMaxEmojiScrollRows() {
            return Math.max(0, getEmojiRowCount(getEmojiCols()) - EMOJI_GRID_ROWS);
        }

        private boolean hasEmojiScrollbar() {
            return getMaxEmojiScrollRows() > 0;
        }

        private int getEmojiScrollbarThumbHeight() {
            int totalRows = getEmojiRowCount(getEmojiCols());
            if (totalRows <= 0) {
                return emojiBoxH - 2;
            }
            float viewRatio = (float) EMOJI_GRID_ROWS / (float) totalRows;
            return Math.max(8, (int) ((emojiBoxH - 2) * viewRatio));
        }

        private int getEmojiScrollbarThumbY() {
            int maxRows = getMaxEmojiScrollRows();
            int thumbH = getEmojiScrollbarThumbHeight();
            int travel = emojiBoxH - 2 - thumbH;
            return emojiBoxY + 1
                + (travel <= 0 || maxRows <= 0 ? 0 : (int) ((float) emojiScrollRows / maxRows * travel));
        }
    }

    private static final class PackEmojiItem {

        final File file;
        final String name;
        final String extension;
        final PackPreviewEmoji preview;

        private PackEmojiItem(File file, String name, String extension, PackPreviewEmoji preview) {
            this.file = file;
            this.name = name;
            this.extension = extension;
            this.preview = preview;
        }
    }

    private static final class PackPreviewEmoji implements RenderableEmoji {

        private final File file;
        private final ResourceLocation resourceLocation;
        private FileTexture texture;

        private PackPreviewEmoji(File file, String packFolder, String emojiName) {
            this.file = file;
            int pathHash = Math.abs((packFolder + ":" + emojiName + ":" + file.getAbsolutePath()).hashCode());
            this.resourceLocation = new ResourceLocation(
                Minemoticon.MODID,
                "textures/packmanager/" + packFolder + "/" + pathHash);
        }

        @Override
        public ResourceLocation getResourceLocation() {
            if (texture == null) {
                texture = new FileTexture(file);
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .loadTexture(resourceLocation, texture);
            }
            return resourceLocation;
        }

        @Override
        public boolean isLoaded() {
            return texture != null && texture.isUploaded();
        }

        @Override
        public float[] getUV() {
            return texture != null ? texture.getCurrentUV() : null;
        }

        public void destroy() {
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(resourceLocation);
            texture = null;
        }
    }

    private static final class NewPackPromptScreen extends GuiScreen {

        private static final int BTN_CREATE = 1;
        private static final int BTN_CANCEL = 2;

        private final PackManagementScreen parent;
        private GuiTextField nameField;
        private String errorText;

        private NewPackPromptScreen(PackManagementScreen parent) {
            this.parent = parent;
        }

        @Override
        public void initGui() {
            buttonList.clear();

            int panelW = 220;
            int panelH = 82;
            int panelX = (width - panelW) / 2;
            int panelY = (height - panelH) / 2;

            nameField = new GuiTextField(fontRendererObj, panelX + 10, panelY + 24, panelW - 20, 16);
            nameField.setMaxStringLength(64);
            nameField.setText(DEFAULT_NEW_PACK_DISPLAY_NAME);
            nameField.setFocused(true);
            nameField.setCursorPositionEnd();

            int btnY = panelY + panelH - 24;
            buttonList.add(new GuiButton(BTN_CREATE, panelX + 10, btnY, 98, 20, "Create"));
            buttonList.add(new GuiButton(BTN_CANCEL, panelX + panelW - 108, btnY, 98, 20, "Cancel"));
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            nameField.updateCursorCounter();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            parent.drawScreen(-1, -1, partialTicks);
            drawRect(0, 0, width, height, 0x90000000);

            int panelW = 220;
            int panelH = 82;
            int panelX = (width - panelW) / 2;
            int panelY = (height - panelH) / 2;

            Gui.drawRect(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xA0A0A0A0);
            Gui.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);

            drawCenteredString(fontRendererObj, "New Pack", width / 2, panelY + 8, 0xFFFFFF);
            fontRendererObj.drawStringWithShadow("Name", panelX + 10, panelY + 14, 0x909090);
            nameField.drawTextBox();

            if (errorText != null) {
                drawCenteredString(fontRendererObj, errorText, width / 2, panelY + 44, 0xFFFF8080);
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button.id == BTN_CANCEL) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (button.id == BTN_CREATE) {
                submit();
            }
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            nameField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            if (keyCode == 1) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (keyCode == 15) {
                nameField.setFocused(true);
                return;
            }
            if (keyCode == 28 || keyCode == 156) {
                submit();
                return;
            }
            if (nameField.textboxKeyTyped(typedChar, keyCode)) {
                errorText = null;
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        private void submit() {
            String requestedName = nameField.getText()
                .trim();
            if (requestedName.isEmpty()) {
                errorText = "Pack name cannot be empty";
                return;
            }

            parent.queueNewPack(requestedName);
            mc.displayGuiScreen(parent);
        }
    }

    private void drawButton(int x, int y, int w, int h, String label, boolean hovered, int borderColor) {
        Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, borderColor);
        Gui.drawRect(x, y, x + w, y + h, hovered ? 0xFF303030 : 0xFF101010);
        drawCenteredString(fontRendererObj, label, x + w / 2, y + 3, 0xFFFFFF);
    }

    private void drawFieldLabel(String text, int x, int y) {
        drawFieldLabel(text, x, y, 0x909090);
    }

    private void drawFieldLabel(String text, int x, int y, int color) {
        fontRendererObj.drawStringWithShadow(text, x, y, color);
    }

    private String trimToWidthWithEllipsis(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = fontRendererObj.getStringWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return fontRendererObj.trimStringToWidth(text, maxWidth);
        }

        return fontRendererObj.trimStringToWidth(text, maxWidth - ellipsisWidth) + ellipsis;
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        if (h > 2) {
            Gui.drawRect(x, y + 1, x + 1, y + h - 1, color);
            Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
        }
    }

    private void setTooltip(int mouseX, int mouseY, String text) {
        tooltipText = text;
        tooltipX = mouseX;
        tooltipY = mouseY;
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private final class FocusableField {

        private final PackCard card;
        private final FocusField field;

        private FocusableField(PackCard card, FocusField field) {
            this.card = card;
            this.field = field;
        }

        private boolean isFocused() {
            return card.isFieldFocused(field);
        }

        private void focus() {
            card.focusField(field);
        }
    }
}
