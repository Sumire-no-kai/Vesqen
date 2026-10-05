package io.github.sumirenokai.vesqen.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy

/** The compact bar height before the system navigation inset is applied. */
internal val CompactNavigationBarContentHeight = 60.dp

/** The rail beside wide windows; destination layouts measure the width left beside it. */
internal val NavigationRailWidth = 96.dp

private val NavigationSelectionEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// The item's natural top-icon layout is taller than the 60 dp bar. Lifting the label and its mark
// keeps breathing room above the bottom edge when there is no gesture inset below the bar.
private val CompactNavigationLabelOffset = (-4).dp

// B · Paper & Sound §5: paper-nav ground with a top hairline, line icons, the selected item in
// ink at weight 600 with a 16 × 2 dp Moss mark under its label, and no pill indicator.
@Composable
fun VesqenNavigation(
    selectedDestination: VesqenDestination,
    onDestinationSelected: (VesqenDestination) -> Unit,
    useNavigationRail: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalVesqenColors.current
    if (useNavigationRail) {
        NavigationRail(modifier = modifier, containerColor = colors.navigation) {
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
                .drawBehind {
                    drawLine(colors.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }
                .navigationBarsPadding()
                .height(CompactNavigationBarContentHeight)
                .testTag("vesqen.navigation.compact"),
            containerColor = colors.navigation,
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
        targetValue = if (selected && !motionPolicy.reduceMotion) 1.08f else 1f,
        animationSpec = tween(
            durationMillis = if (motionPolicy.reduceMotion) 0 else 180,
            easing = NavigationSelectionEasing,
        ),
        label = "vesqen.navigation-icon-scale",
    )
    val mark by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(if (motionPolicy.reduceMotion) 0 else 180, easing = NavigationSelectionEasing),
        label = "vesqen.navigation-mark",
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
            Column(
                modifier = Modifier.offset(y = CompactNavigationLabelOffset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        letterSpacing = 0.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.size(width = 16.dp, height = 2.dp).background(mark, RoundedCornerShape(1.dp)))
            }
        },
        iconPosition = NavigationItemIconPosition.Top,
        colors = ShortNavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            selectedIndicatorColor = Color.Transparent,
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
        label = {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        },
        alwaysShowLabel = true,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun DestinationIcon(destination: VesqenDestination, modifier: Modifier = Modifier) {
    Icon(imageVector = destination.icon, contentDescription = null, modifier = modifier)
}

private val VesqenDestination.icon: ImageVector
    get() = when (this) {
        VesqenDestination.LIBRARY -> Icons.Outlined.LibraryMusic
        VesqenDestination.NOW -> Icons.Outlined.PlayCircle
        VesqenDestination.SETTINGS -> Icons.Outlined.Settings
        VesqenDestination.CHAIN -> Icons.Outlined.AccountTree
        VesqenDestination.ABOUT -> Icons.Outlined.Info
        VesqenDestination.PRIVACY_POLICY -> Icons.Outlined.PrivacyTip
        VesqenDestination.LICENSES -> Icons.Outlined.Description
        VesqenDestination.USAGE_STATISTICS -> Icons.Outlined.BarChart
        VesqenDestination.DEVICE_REPORT -> Icons.Outlined.BugReport
    }
