package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.Query;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Command parsing is separate from Bukkit and does not touch server state. */
public final class CommandOptions {
    private final Query.Mode mode;
    private final Query.Match match;
    private final Query.LoreMatch loreMatch;
    private final String keyword;

    public CommandOptions(Query.Mode mode, Query.Match match, Query.LoreMatch loreMatch, String keyword) {
        this.mode = mode;
        this.match = match;
        this.loreMatch = loreMatch;
        this.keyword = keyword;
    }

    public Query.Mode mode() { return mode; }
    public Query.Match match() { return match; }
    public Query.LoreMatch loreMatch() { return loreMatch; }
    public String keyword() { return keyword; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandOptions)) return false;
        CommandOptions value = (CommandOptions) other;
        return mode == value.mode && match == value.match && loreMatch == value.loreMatch && Objects.equals(keyword, value.keyword);
    }

    @Override public int hashCode() { return Objects.hash(mode, match, loreMatch, keyword); }

    @Override public String toString() {
        return "CommandOptions[mode=" + mode + ", match=" + match + ", loreMatch=" + loreMatch + ", keyword=" + keyword + "]";
    }
    public static CommandOptions parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException(Language.tr("command.missing-mode"));
        Query.Mode mode;
        try { mode = Query.Mode.valueOf(args[0].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException(Language.tr("command.unknown-mode", "mode", args[0])); }
        if (mode == Query.Mode.SAME) {
            if (args.length != 1) throw new IllegalArgumentException(Language.tr("command.same-syntax"));
            return new CommandOptions(mode, Query.Match.EXACT, Query.LoreMatch.ALL, "");
        }
        Query.Match match = Query.Match.FUZZY;
        Query.LoreMatch lore = Query.LoreMatch.ALL;
        boolean hasMatch = false, hasLore = false;
        int i = 1;
        for (; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.equals("--")) { i++; break; }
            if (arg.equals("exact") || arg.equals("fuzzy")) {
                if (hasMatch) throw new IllegalArgumentException(Language.tr("command.duplicate-match"));
                match = Query.Match.valueOf(arg.toUpperCase(Locale.ROOT));
                hasMatch = true;
            } else if (mode == Query.Mode.LORE && (arg.equals("any") || arg.equals("all"))) {
                if (hasLore) throw new IllegalArgumentException(Language.tr("command.duplicate-lore-match"));
                lore = Query.LoreMatch.valueOf(arg.toUpperCase(Locale.ROOT));
                hasLore = true;
            } else break;
        }
        String keyword = strip(String.join(" ", Arrays.copyOfRange(args, i, args.length)));
        if (mode == Query.Mode.MATERIAL && keyword.isEmpty()) match = Query.Match.EXACT;
        return new CommandOptions(mode, match, lore, keyword);
    }

    private static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
}
