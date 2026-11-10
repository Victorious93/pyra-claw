package com.victorious.pyraclaw.ui.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.ui.components.CenteredState
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun HelpScreen(initiallyExpanded: Set<String> = setOf("start")) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val results = remember(query) { HelpContent.search(query) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("help"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Help & manual", style = MaterialTheme.typography.headlineMedium)
            Text("Everything in docs/MANUAL.md, inside the app.", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
        }
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().testTag("helpSearch"), singleLine = true,
                placeholder = { Text("Search the manual") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
            )
        }
        if (results.isEmpty()) item {
            CenteredState(Icons.AutoMirrored.Filled.MenuBook, "No matches", "Try a shorter word, like \"wake\" or \"timer\".")
        }
        items(results, key = { it.id }) { s ->
            val open = query.isNotBlank() || s.id in expanded
            NeonCard(accent = if (open) Neon.Cyan.copy(alpha = 0.4f) else Neon.Outline, padding = 14.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(s.summary, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
                    }
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand", tint = Neon.Cyan)
                }
                AnimatedVisibility(open) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.body.forEach { p -> Text(p, style = MaterialTheme.typography.bodyMedium, color = Neon.Text) }
                    }
                }
            }
        }
    }
}
