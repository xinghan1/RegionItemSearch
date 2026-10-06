package com.xinghan.itemsearch.item;

import com.xinghan.itemsearch.Compat;
import java.util.List;
import java.util.Objects;

/** Matching is independent of Bukkit so it can safely execute on the scanning worker. */
public final class Query {
    private final Mode mode;
    private final Match match;
    private final LoreMatch loreMatch;
    private final ItemData template;
    private final List<String> terms;
    private final String label;
    public Query(Mode mode, Match match, LoreMatch loreMatch, ItemData template, List<String> terms, String label) {
        Objects.requireNonNull(mode, "mode");
        match = match == null ? Match.FUZZY : match;
        loreMatch = loreMatch == null ? LoreMatch.ALL : loreMatch;
        terms = terms == null ? Compat.list() : Compat.listCopy(terms);
        label = label == null ? "" : label;
        this.mode = mode;
        this.match = match;
        this.loreMatch = loreMatch;
        this.template = template;
        this.terms = terms;
        this.label = label;
    }
    public Mode mode() { return mode; }
    public Match match() { return match; }
    public LoreMatch loreMatch() { return loreMatch; }
    public ItemData template() { return template; }
    public List<String> terms() { return terms; }
    public String label() { return label; }
    public enum Mode { SAME, NAME, LORE, ID, MATERIAL }
    public enum Match { EXACT, FUZZY }
    public enum LoreMatch { ANY, ALL }


    public boolean matches(ItemData candidate) {
        if (candidate == null) return false;
        switch (mode) {
            case SAME: return template != null && template.fingerprint().equals(candidate.fingerprint());
            case NAME: return nameMatches(candidate);
            case LORE: return loreMatches(candidate);
            case ID: return idMatches(candidate);
            case MATERIAL: return materialMatches(candidate);
            default: throw new AssertionError(mode);
        }
    }

    private boolean nameMatches(ItemData candidate) {
        List<String> targets = meaningful(terms.isEmpty() && template != null ? Compat.list(template.name()) : terms);
        return !targets.isEmpty() && targets.stream().allMatch(target -> textMatches(candidate.name(), target));
    }

    private boolean loreMatches(ItemData candidate) {
        List<String> targets = meaningful(terms.isEmpty() && template != null ? template.lore() : terms);
        if (targets.isEmpty()) return false;
        if (loreMatch == LoreMatch.ANY) {
            return targets.stream().anyMatch(target -> candidate.lore().stream().anyMatch(line -> textMatches(line, target)));
        }
        return targets.stream().allMatch(target -> candidate.lore().stream().anyMatch(line -> textMatches(line, target)));
    }

    private boolean idMatches(ItemData candidate) {
        if (terms.isEmpty()) {
            return template != null && !Compat.isBlank(template.mmoType()) && !Compat.isBlank(template.mmoId())
                    && !Compat.isBlank(candidate.mmoType()) && !Compat.isBlank(candidate.mmoId())
                    && textMatches(candidate.mmoType(), template.mmoType())
                    && textMatches(candidate.mmoId(), template.mmoId());
        }
        List<String> targets = meaningful(terms);
        if (targets.isEmpty() || Compat.isBlank(candidate.mmoId())) return false;
        if (targets.size() == 2) {
            return !Compat.isBlank(candidate.mmoType()) && textMatches(candidate.mmoType(), targets.get(0))
                    && textMatches(candidate.mmoId(), targets.get(1));
        }
        if (targets.size() != 1) return false;
        String target = targets.get(0);
        int separator = target.indexOf('/');
        if (separator < 0) return textMatches(candidate.mmoId(), target);
        String type = Compat.strip(target.substring(0, separator));
        String id = Compat.strip(target.substring(separator + 1));
        return !Compat.isBlank(type) && !Compat.isBlank(id) && !Compat.isBlank(candidate.mmoType())
                && textMatches(candidate.mmoType(), type) && textMatches(candidate.mmoId(), id);
    }

    private boolean materialMatches(ItemData candidate) {
        String material = normalizedMaterial(candidate.material());
        if (material.isEmpty()) return false;
        if (terms.isEmpty()) {
            // Held templates select one exact material, independently of metadata and flags.
            return template != null && material.equals(normalizedMaterial(template.material()));
        }
        List<String> targets = meaningful(terms).stream().map(Query::normalizedMaterial)
                .filter(target -> !target.isEmpty()).collect(java.util.stream.Collectors.toList());
        return !targets.isEmpty() && targets.stream().allMatch(target ->
                match == Match.EXACT ? material.equals(target) : material.contains(target));
    }

    private static String normalizedMaterial(String value) {
        String normalized = TextNormalizer.normalize(value);
        return normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized;
    }

    private boolean textMatches(String candidate, String target) {
        String normalizedTarget = TextNormalizer.normalize(target);
        if (normalizedTarget.isEmpty()) return false;
        String normalizedCandidate = TextNormalizer.normalize(candidate);
        return match == Match.EXACT ? normalizedCandidate.equals(normalizedTarget) : normalizedCandidate.contains(normalizedTarget);
    }

    private static List<String> meaningful(List<String> values) {
        return values.stream().filter(Objects::nonNull).map(Compat::strip)
                .filter(value -> !TextNormalizer.normalize(value).isEmpty()).collect(java.util.stream.Collectors.toList());
    }
    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof Query)) return false;
        Query other = (Query) value;
        return java.util.Objects.equals(mode, other.mode) && java.util.Objects.equals(match, other.match) && java.util.Objects.equals(loreMatch, other.loreMatch) && java.util.Objects.equals(template, other.template) && java.util.Objects.equals(terms, other.terms) && java.util.Objects.equals(label, other.label);
    }
    @Override public int hashCode() { return java.util.Objects.hash(mode, match, loreMatch, template, terms, label); }
    @Override public String toString() { return "Query[" + "mode=" + mode + ", " + "match=" + match + ", " + "loreMatch=" + loreMatch + ", " + "template=" + template + ", " + "terms=" + terms + ", " + "label=" + label + "]"; }
}
