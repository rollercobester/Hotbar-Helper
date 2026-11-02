package qoby.hotbar_helper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable item picker for the refill filter. The catalog is the item
 * registry, so items added by other mods show up here too.
 */
public class HotbarHelperWhitelistScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 56;
    private static final int MAX_SUGGESTIONS = 6;
    private static final int SEARCH_LIMIT = 80;

    private final Screen parent;
    private final HotbarHelperConfig config;
    private final List<ItemChoice> catalog = new ArrayList<>();
    private final List<ItemChoice> suggestions = new ArrayList<>();
    private final List<ItemChoice> selected = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Row> drawnRows = new ArrayList<>();

    private EditBox searchBox;
    private String searchText = "";
    private int suggestionScroll;
    private int suggestionCursor;
    private int whitelistScroll;
    private int left;
    private int contentWidth;
    private int suggestionTop = -1;
    private int suggestionBottom = -1;
    private int whitelistTop = -1;
    private int whitelistBottom = -1;
    private int whitelistLabelY = -1;
    private int noMatchY = -1;
    private int emptyWhitelistY = -1;
    private int visibleWhitelistRows = 1;
    private boolean layoutDirty;

    public HotbarHelperWhitelistScreen(Screen parent, HotbarHelperConfig config) {
        super(Component.literal("Item refill filter"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();
        if (catalog.isEmpty()) buildCatalog();
        reloadSelected();

        contentWidth = Math.min(360, this.width - 40);
        left = (this.width - contentWidth) / 2;

        addRenderableWidget(CycleButton.builder((HotbarHelperConfig.RefillFilterMode mode) -> Component.literal(mode.label()), config.refillFilterMode())
                .withValues(HotbarHelperConfig.RefillFilterMode.WHITELIST, HotbarHelperConfig.RefillFilterMode.BLACKLIST)
                .create(left, 24, contentWidth, 20, Component.literal("Filter"), (button, mode) -> config.setRefillFilterMode(mode)));

        searchBox = new EditBox(this.font, left, 48, contentWidth, 20, Component.literal("Search items"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items or mods"));
        searchBox.setValue(searchText);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());

        updateSuggestions();
        layoutRows();
    }

    private void onSearchChanged(String text) {
        searchText = text == null ? "" : text;
        suggestionScroll = 0;
        suggestionCursor = 0;
        updateSuggestions();
        requestLayout();
    }

    private void buildCatalog() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            ItemStack stack = new ItemStack(item);
            String name;
            try {
                name = stack.getHoverName().getString();
            } catch (RuntimeException e) {
                name = id.getPath();
            }
            catalog.add(new ItemChoice(item, stack, id, name, id.toString()));
        }
        catalog.sort(Comparator.comparing((ItemChoice choice) -> choice.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(choice -> choice.idString));
    }

    private void reloadSelected() {
        selected.clear();
        for (String id : config.refillWhitelistIds()) {
            selected.add(resolve(id));
        }
    }

    private ItemChoice resolve(String idString) {
        Identifier id = Identifier.tryParse(idString);
        if (id != null) {
            var found = BuiltInRegistries.ITEM.getOptional(id);
            if (found.isEmpty()) {
                return new ItemChoice(null, ItemStack.EMPTY, id, idString, idString);
            }
            Item item = found.get();
            ItemStack stack = new ItemStack(item);
            String name;
            try {
                name = stack.getHoverName().getString();
            } catch (RuntimeException e) {
                name = id.getPath();
            }
            return new ItemChoice(item, stack, id, name, id.toString());
        }
        return new ItemChoice(null, ItemStack.EMPTY, id, idString, idString);
    }

    private void updateSuggestions() {
        suggestions.clear();
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return;

        Set<String> taken = new HashSet<>(config.refillWhitelistIds());
        List<ItemChoice> matches = new ArrayList<>();
        for (ItemChoice choice : catalog) {
            if (taken.contains(choice.idString)) continue;
            String name = choice.name.toLowerCase(Locale.ROOT);
            if (name.contains(query) || choice.idString.contains(query)) {
                matches.add(choice);
            }
        }
        matches.sort(Comparator
                .comparingInt((ItemChoice choice) -> rank(choice, query))
                .thenComparing(choice -> choice.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(choice -> choice.idString));
        int limit = Math.min(SEARCH_LIMIT, matches.size());
        for (int i = 0; i < limit; i++) suggestions.add(matches.get(i));
    }

    private static int rank(ItemChoice choice, String query) {
        String name = choice.name.toLowerCase(Locale.ROOT);
        String path = choice.id == null ? "" : choice.id.getPath();
        if (name.equals(query) || path.equals(query) || choice.idString.equals(query)) return 0;
        if (name.startsWith(query) || path.startsWith(query) || choice.idString.startsWith(query)) return 1;
        if (choice.id != null && choice.id.getNamespace().contains(query)) return 2;
        return 3;
    }

    private void layoutRows() {
        for (Button button : rowButtons) removeWidget(button);
        rowButtons.clear();
        drawnRows.clear();

        int y = searchBox.getY() + searchBox.getHeight() + 2;
        suggestionTop = y;
        boolean searching = !searchText.isBlank();
        int maxSuggestionRows = Math.max(3, Math.min(MAX_SUGGESTIONS, (this.height - 174) / ROW_HEIGHT));
        if (!searching) {
            noMatchY = -1;
        } else if (suggestions.isEmpty()) {
            noMatchY = y + 4;
            y += 18;
        } else {
            noMatchY = -1;
            int visible = Math.min(maxSuggestionRows, suggestions.size());
            suggestionScroll = clamp(suggestionScroll, 0, Math.max(0, suggestions.size() - visible));
            suggestionCursor = clamp(suggestionCursor, 0, suggestions.size() - 1);
            if (suggestionCursor < suggestionScroll) suggestionScroll = suggestionCursor;
            if (suggestionCursor >= suggestionScroll + visible) suggestionScroll = suggestionCursor - visible + 1;
            for (int i = 0; i < visible; i++) {
                int index = suggestionScroll + i;
                y = addRow(suggestions.get(index), index, y, true);
            }
        }
        suggestionBottom = y;

        y += 10;
        whitelistLabelY = y;
        y += 14;
        whitelistTop = y;
        int listBottom = this.height - 36;
        visibleWhitelistRows = Math.max(1, (listBottom - y) / ROW_HEIGHT);
        whitelistScroll = clamp(whitelistScroll, 0, Math.max(0, selected.size() - visibleWhitelistRows));
        if (selected.isEmpty()) {
            emptyWhitelistY = y + 4;
            y += 18;
        } else {
            emptyWhitelistY = -1;
            int shown = Math.min(visibleWhitelistRows, selected.size() - whitelistScroll);
            for (int i = 0; i < shown; i++) {
                int index = whitelistScroll + i;
                y = addRow(selected.get(index), index, y, false);
            }
        }
        whitelistBottom = y;
    }

    private int addRow(ItemChoice choice, int index, int y, boolean suggestion) {
        int buttonX = left + contentWidth - BUTTON_WIDTH;
        Button button = Button.builder(Component.literal(suggestion ? "Add" : "Remove"), pressed -> {
            if (suggestion) addChoice(choice);
            else removeChoice(choice);
        }).bounds(buttonX, y + 1, BUTTON_WIDTH, 20).build();
        addRenderableWidget(button);
        rowButtons.add(button);
        drawnRows.add(new Row(choice, index, left, y, buttonX, suggestion));
        return y + ROW_HEIGHT;
    }

    private void addChoice(ItemChoice choice) {
        if (!config.addRefillWhitelistId(choice.idString)) return;
        reloadSelected();
        updateSuggestions();
        if (suggestionCursor >= suggestions.size()) suggestionCursor = Math.max(0, suggestions.size() - 1);
        requestLayout();
        focusSearch();
    }

    private void removeChoice(ItemChoice choice) {
        config.removeRefillWhitelistId(choice.idString);
        reloadSelected();
        updateSuggestions();
        requestLayout();
    }

    private void requestLayout() {
        layoutDirty = true;
    }

    private void focusSearch() {
        searchBox.setFocused(true);
        setFocused(searchBox);
    }

    private void moveCursor(int delta) {
        if (suggestions.isEmpty()) return;
        suggestionCursor = clamp(suggestionCursor + delta, 0, suggestions.size() - 1);
        requestLayout();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox != null && searchBox.isFocused()) {
            int keyCode = event.key();
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                moveCursor(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                moveCursor(-1);
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && !suggestions.isEmpty()) {
                addChoice(suggestions.get(suggestionCursor));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = scrollY > 0 ? -1 : 1;
        if (mouseY >= suggestionTop && mouseY < suggestionBottom && suggestions.size() > 1) {
            int visible = Math.max(1, (suggestionBottom - suggestionTop) / ROW_HEIGHT);
            if (suggestions.size() > visible) {
                suggestionScroll = clamp(suggestionScroll + direction, 0, suggestions.size() - visible);
                suggestionCursor = clamp(suggestionCursor, suggestionScroll, suggestionScroll + visible - 1);
                requestLayout();
                return true;
            }
        }
        if (mouseY >= whitelistTop && mouseY < whitelistBottom && selected.size() > visibleWhitelistRows) {
            whitelistScroll = clamp(whitelistScroll + direction, 0, selected.size() - visibleWhitelistRows);
            requestLayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() != 0) return false;
        for (Row row : drawnRows) {
            if (row.suggestion && row.contains(event.x(), event.y())) {
                addChoice(row.choice);
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (layoutDirty) {
            layoutDirty = false;
            layoutRows();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        ItemChoice tooltip = null;
        for (Row row : drawnRows) {
            boolean hovered = row.contains(mouseX, mouseY);
            boolean current = row.suggestion && row.index == suggestionCursor;
            int color = current ? 0xC02E4A6E : hovered ? 0xC03A3A3A : 0xA0181818;
            graphics.fill(row.x, row.y, row.textRight, row.y + ROW_HEIGHT, color);
            if (!row.choice.stack.isEmpty()) {
                graphics.renderItem(row.choice.stack, row.x + 3, row.y + 3);
            }
            int textWidth = row.textRight - row.x - 32;
            graphics.drawString(this.font, fit(row.choice.name, textWidth), row.x + 22, row.y + 2, 0xFFFFFF);
            graphics.drawString(this.font, fit(row.choice.idString, textWidth), row.x + 22, row.y + 12, 0xFFA0A0A0);
            if (hovered && !row.choice.stack.isEmpty()) tooltip = row.choice;
        }
        drawScrollbar(graphics, suggestionTop, suggestionBottom, suggestionScroll, suggestions.size(), MAX_SUGGESTIONS);
        drawScrollbar(graphics, whitelistTop, whitelistBottom, whitelistScroll, selected.size(), visibleWhitelistRows);

        if (noMatchY >= 0) {
            graphics.drawString(this.font, "No matching items", left + 4, noMatchY, 0xFFA0A0A0);
        }
        String listName = config.refillFilterMode() == HotbarHelperConfig.RefillFilterMode.BLACKLIST ? "Blacklist" : "Whitelist";
        String whitelistLabel = selected.isEmpty() ? listName : listName + " (" + selected.size() + ")";
        if (whitelistLabelY >= 0) {
            graphics.drawString(this.font, whitelistLabel, left, whitelistLabelY, 0xFFFFFF);
        }
        if (emptyWhitelistY >= 0) {
            String empty = config.refillFilterMode() == HotbarHelperConfig.RefillFilterMode.BLACKLIST
                    ? "Every item refills until you blacklist some."
                    : "Every item refills until you whitelist some.";
            graphics.drawString(this.font, fit(empty, contentWidth - 8), left + 4, emptyWhitelistY, 0xFFA0A0A0);
        }
        if (tooltip != null) {
            graphics.setTooltipForNextFrame(this.font, tooltip.stack, mouseX, mouseY);
        }
    }

    private void drawScrollbar(GuiGraphics graphics, int top, int bottom, int scroll, int total, int visible) {
        if (top < 0 || total <= visible) return;
        int height = Math.max(0, bottom - top);
        if (height <= 0) return;
        int x = left + contentWidth - BUTTON_WIDTH - 6;
        int thumbHeight = Math.max(8, height * visible / total);
        int maxScroll = Math.max(1, total - visible);
        int thumbY = top + (height - thumbHeight) * scroll / maxScroll;
        graphics.fill(x, top, x + 3, bottom, 0xFF202020);
        graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0xFFB0B0B0);
    }

    private String fit(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String trimmed = text;
        while (trimmed.length() > 1 && this.font.width(trimmed + "...") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "...";
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onClose() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config.save(configDir);
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    private record ItemChoice(Item item, ItemStack stack, Identifier id, String name, String idString) {
    }

    private static final class Row {
        final ItemChoice choice;
        final int index;
        final int x;
        final int y;
        final int textRight;
        final boolean suggestion;

        Row(ItemChoice choice, int index, int x, int y, int textRight, boolean suggestion) {
            this.choice = choice;
            this.index = index;
            this.x = x;
            this.y = y;
            this.textRight = textRight;
            this.suggestion = suggestion;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < textRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
        }
    }
}
