package io.github.sumirenokai.vesqen.ui.navigation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.sumirenokai.vesqen.R

@Composable
fun VesqenNavigation(
    selectedDestination: VesqenDestination,
    onDestinationSelected: (VesqenDestination) -> Unit,
    useNavigationRail: Boolean,
    modifier: Modifier = Modifier,
) {
    if (useNavigationRail) {
        NavigationRail(modifier = modifier) {
            VesqenDestination.entries.forEach { destination ->
                DestinationRailItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    } else {
        NavigationBar(modifier = modifier) {
            VesqenDestination.entries.forEach { destination ->
                DestinationBarItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.DestinationBarItem(
    destination: VesqenDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    NavigationBarItem(
        modifier = Modifier.testTag(destination.testTag),
        selected = selected,
        onClick = onClick,
        icon = { DestinationIcon(destination) },
        label = { Text(label) },
        alwaysShowLabel = true,
    )
}

@Composable
private fun ColumnScope.DestinationRailItem(
    destination: VesqenDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    NavigationRailItem(
        modifier = Modifier.testTag(destination.testTag),
        selected = selected,
        onClick = onClick,
        icon = { DestinationIcon(destination) },
        label = { Text(label) },
        alwaysShowLabel = true,
    )
}

@Composable
private fun DestinationIcon(destination: VesqenDestination) {
    Icon(
        imageVector = destination.icon,
        contentDescription = null,
    )
}

private val VesqenDestination.icon: ImageVector
    get() = when (this) {
        VesqenDestination.LIBRARY -> Icons.Filled.LibraryMusic
        VesqenDestination.NOW -> Icons.Filled.PlayCircle
        VesqenDestination.CHAIN -> Icons.Filled.AccountTree
    }
