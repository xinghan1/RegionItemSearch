package com.xinghan.itemsearch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import java.util.stream.Collectors;

/** One chat palette for results, progress, editing feedback and hover text. */
final class ChatStyle {
    private ChatStyle() { }

    static Component simplify(Component content) {
        TextColor defaultColor = TextColor.fromHexString(Language.tr("style.default-color"));
        if (defaultColor == null) defaultColor = NamedTextColor.GRAY;
        return (Language.bool("style.neutral-colors", false) ? normalize(content) : content)
                .colorIfAbsent(defaultColor);
    }

    private static Component normalize(Component content) {
        Component result = content;
        TextColor color = content.color();
        if (color != null) {
            NamedTextColor neutral = color.equals(NamedTextColor.BLACK) || color.equals(NamedTextColor.DARK_GRAY)
                    ? NamedTextColor.DARK_GRAY
                    : color.equals(NamedTextColor.WHITE) || color.equals(NamedTextColor.YELLOW)
                    || color.equals(NamedTextColor.GOLD) || color.equals(NamedTextColor.RED)
                    || color.equals(NamedTextColor.GREEN) ? NamedTextColor.WHITE : NamedTextColor.GRAY;
            result = result.color(neutral);
        }
        result = result.children(content.children().stream().map(ChatStyle::normalize).collect(Collectors.toList()));
        HoverEvent<?> hover = content.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component) {
            result = result.hoverEvent(HoverEvent.showText(simplify((Component) hover.value())));
        }
        return result;
    }
}
