package com.emofah.agencies

import com.emofah.agencies.ui.navigation.EmofahDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class EmofahNavigationTest {
    @Test
    fun navigationHasSevenBlueprintDestinations() {
        assertEquals(7, EmofahDestination.entries.size)
        assertEquals(5, EmofahDestination.entries.count { it.showInBottomBar })
    }
}
