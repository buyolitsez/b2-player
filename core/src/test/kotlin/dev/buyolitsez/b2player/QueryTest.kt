package dev.buyolitsez.b2player

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryTest {
    private fun key(vararg fields: String) = searchFold(fields.joinToString("\u0000"))

    @Test fun typoMatchesStayAfterExactOnes() {
        // One album with "letova" spelled out must not hide the albums of "Летов" found with a typo.
        val keys = listOf(key("Концерт", "Егор и Сергей Летовы"), key("Live", "Летова"), key("Other", "Artist"))
        assertEquals(listOf(keys[1], keys[0]), keys.filterBy(Query("летова")) { it })
    }

    @Test fun squeezedWordsDoNotRunAcrossFields() {
        // "letov" + "acoustic" is not "letova" spelled out, only a typo away.
        assertEquals(3, Query("letova").rank(key("Квартирник", "Егор Летов", "Acoustic")))
        assertEquals(2, Query("darkside").rank(key("The Dark Side of the Moon", "Pink Floyd")))
    }
}
