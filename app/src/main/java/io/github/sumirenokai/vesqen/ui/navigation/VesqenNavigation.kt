package io.github.sumirenokai.vesqen.ui.navigation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The compact bar's content height; system navigation insets are added by Material. */
internal val CompactNavigationBarContentHeight = 64.dp

@Composable
fun VesqenNavigation(
    selectedDestination: VesqenDestination,
    onDestinationSelected: (VesqenDestination) -> Unit,
    useNavigationRail: Boolean,
    modifier: Modifier = Modifier,
) {
    if (useNavigationRail) {
        NavigationRail(modifier = modifier) {
            TopLevelDestinations.forEach { destination ->
                DestinationRailItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    } else {
        ShortNavigationBar(
            modifier = modifier.testTag("vesqen.navigation.compact"),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            arrangement = ShortNavigationBarArrangement.EqualWeight,
        ) {
            TopLevelDestinations.forEach { destination ->
                DestinationShortBarItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    }
}

@Composable
private fun DestinationShortBarItem(
    destination: VesqenDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    ShortNavigationBarItem(
        modifier = Modifier.testTag(destination.testTag),
        selected = selected,
        onClick = onClick,
        icon = { DestinationIcon(destination) },
        label = {
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
        iconPosition = NavigationItemIconPosition.Top,
        colors = ShortNavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            selectedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
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
    Icon(imageVector = destination.icon, contentDescription = null)
}

private val VesqenDestination.icon: ImageVector
    get() = when (this) {
        VesqenDestination.LIBRARY -> Icons.Filled.LibraryMusic
        VesqenDestination.NOW -> Icons.Filled.PlayCircle
        VesqenDestination.SETTINGS -> Icons.Filled.Settings
        VesqenDestination.CHAIN -> Icons.Filled.AccountTree
        VesqenDestination.ABOUT -> Icons.Filled.Info
    }
