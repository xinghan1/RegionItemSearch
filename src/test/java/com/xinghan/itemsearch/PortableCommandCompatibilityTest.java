package com.xinghan.itemsearch;

import com.xinghan.itemsearch.item.Query;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Observable command contracts retained when records and Java 11 string helpers are removed. */
class PortableCommandCompatibilityTest {
    @Test void heldMaterialRemainsExactEvenWhenFuzzyWasRequested() {
        CommandOptions options = CommandOptions.parse(new String[]{"material", "fuzzy"});
        assertEquals(Query.Mode.MATERIAL, options.mode());
        assertEquals(Query.Match.EXACT, options.match());
        assertEquals("", options.keyword());
    }

    @Test void unicodeWhitespaceAndKeywordEscapeKeepTheirMeaningOnJavaEight() {
        CommandOptions options = CommandOptions.parse(new String[]{"name", "exact", "\u2003亡灵之刃\u2003"});
        assertEquals("亡灵之刃", options.keyword());
        assertEquals(Query.Match.EXACT, options.match());
        CommandOptions literal = CommandOptions.parse(new String[]{"id", "--", "exact"});
        assertEquals("exact", literal.keyword());
        assertEquals(Query.Match.FUZZY, literal.match());
    }

    @Test void commandOptionsRemainImmutableValuesWithCompatibleAccessors() {
        CommandOptions parsed = CommandOptions.parse(new String[]{"lore", "any", "exact", "封印"});
        CommandOptions copied = new CommandOptions(Query.Mode.LORE, Query.Match.EXACT, Query.LoreMatch.ANY, "封印");
        assertEquals(copied, parsed);
        assertEquals(copied.hashCode(), parsed.hashCode());
        assertNotEquals(copied, new CommandOptions(Query.Mode.LORE, Query.Match.FUZZY, Query.LoreMatch.ANY, "封印"));
    }

    @Test void staleAndAbbreviatedChatTokensNeverResolveToANewResult() {
        UUID current = UUID.fromString("00000001-0001-0001-0001-000000000001");
        assertEquals(1, ResultReferences.index(current, current.toString(), "2", 3));
        assertEquals(2, ResultReferences.page(current, current.toString(), "2", 3));
        assertThrows(IllegalArgumentException.class, () -> ResultReferences.index(current, "1-1-1-1-1", "2", 3));
        assertThrows(IllegalArgumentException.class, () -> ResultReferences.index(UUID.randomUUID(), current.toString(), "2", 3));
    }
}
