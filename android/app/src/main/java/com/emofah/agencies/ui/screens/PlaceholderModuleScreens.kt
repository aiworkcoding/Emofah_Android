package com.emofah.agencies.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.navigation.EmofahDestination

@Composable
fun ModuleScaffoldScreen(
    destination: EmofahDestination,
    stageNote: String,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EmofahEmptyState(
            icon = destination.icon,
            title = destination.label,
            message = stageNote,
            actionLabel = "Back to Home",
            onAction = onBackHome,
        )
    }
}
