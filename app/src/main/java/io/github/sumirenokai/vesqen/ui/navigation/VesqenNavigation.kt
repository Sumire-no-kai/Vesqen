package io.github.sumirenokai.vesqen.ui.navigation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy

/** The compact bar height before the system navigation inset is applied. */
internal val CompactNavigationBarContentHeight = 60.dp

private val CompactNavigationLabelOffset = (-4).dp

private val NavigationSelectionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

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
            modifier = modifier
                .navigationBarsPadding()
                .height(CompactNavigationBarContentHeight)
                .testTag("vesqen.navigation.compact"),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            windowInsets = WindowInsets(0, 0, 0, 0),
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
    val motionPolicy = rememberVesqenMotionPolicy()
    val iconScale by animateFloatAsState(
        targetValue = if (selected && !motionPolicy.reduceMotion) 1.12f else 1f,
        animationSpec = tween(
            durationMillis = if (motionPolicy.reduceMotion) 0 else 180,
            easing = NavigationSelectionEasing,
        ),
        label = "vesqen.navigation-icon-scale",
    )
    ShortNavigationBarItem(
        modifier = Modifier.testTag(destination.testTag),
        selected = selected,
        onClick = onClick,
        icon = {
            DestinationIcon(
                destination = destination,
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            )
        },
        label = {
            Text(
                text = label,
                modifier = Modifier.offset(y = CompactNavigationLabelOffset),
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
private fun DestinationIcon(destination: VesqenDestination, modifier: Modifier = Modifier) {
    Icon(imageVector = destination.icon, contentDescription = null, modifier = modifier)
}

private val VesqenDestination.icon: ImageVector
    get() = when (this) {
        VesqenDestination.LIBRARY -> Icons.Filled.LibraryMusic
        VesqenDestination.NOW -> Icons.Filled.PlayCircle
        VesqenDestination.SETTINGS -> Icons.Filled.Settings
        VesqenDestination.CHAIN -> Icons.Filled.AccountTree
        VesqenDestination.ABOUT -> Icons.Filled.Info
    }
