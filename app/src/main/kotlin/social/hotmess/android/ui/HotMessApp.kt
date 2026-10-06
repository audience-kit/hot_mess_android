package social.hotmess.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import social.hotmess.android.location.LocationAccess
import social.hotmess.android.session.AuthState
import social.hotmess.android.ui.screens.EventScreen
import social.hotmess.android.ui.screens.EventsScreen
import social.hotmess.android.ui.screens.LaunchScreen
import social.hotmess.android.ui.screens.LoginScreen
import social.hotmess.android.ui.screens.MeScreen
import social.hotmess.android.ui.screens.NowScreen
import social.hotmess.android.ui.screens.PeopleScreen
import social.hotmess.android.ui.screens.PersonScreen
import social.hotmess.android.ui.screens.UpdateRequiredScreen
import social.hotmess.android.ui.screens.VenueChatScreen
import social.hotmess.android.ui.screens.VenueScreen
import social.hotmess.android.ui.screens.VenuesScreen
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.AppTab

// Routes. Each tab's root, and the detail screens any tab can push.
@Serializable data object NowRoute
@Serializable data object EventsRoute
@Serializable data object VenuesRoute
@Serializable data object PeopleRoute
@Serializable data object MeRoute
@Serializable data class VenueRoute(val id: String)
@Serializable data class EventRoute(val id: String)
@Serializable data class PersonRoute(val id: String)
@Serializable data class ChatRoute(val venueId: String, val venueName: String)

private val AppTab.route: Any
    get() = when (this) {
        AppTab.NOW -> NowRoute
        AppTab.EVENTS -> EventsRoute
        AppTab.VENUES -> VenuesRoute
        AppTab.PEOPLE -> PeopleRoute
        AppTab.ME -> MeRoute
    }

private val AppTab.title: String
    get() = when (this) {
        AppTab.NOW -> "Now"
        AppTab.EVENTS -> "Events"
        AppTab.VENUES -> "Venues"
        AppTab.PEOPLE -> "People"
        AppTab.ME -> "Me"
    }

private val AppTab.icon: ImageVector
    get() = when (this) {
        AppTab.NOW -> Icons.Rounded.Home
        AppTab.EVENTS -> Icons.Rounded.CalendarMonth
        AppTab.VENUES -> Icons.Rounded.Place
        AppTab.PEOPLE -> Icons.Rounded.People
        AppTab.ME -> Icons.Rounded.AccountCircle
    }

/** Where a row or link can send the user. */
interface Navigator {
    fun open(route: AppRoute)
    fun openChat(venueId: String, venueName: String)
    fun back()
}

/** Update required, launch, sign-in or the app, by session state. */
@Composable
fun HotMessApp(links: Flow<AppRoute>, onSignIn: () -> Unit) {
    val graph = LocalAppGraph.current
    val state by graph.session.state.collectAsStateWithLifecycle()
    val requiredVersion by graph.session.requiredVersion.collectAsStateWithLifecycle()

    Crossfade(
        targetState = when {
            requiredVersion != null -> 0
            state == AuthState.Restoring -> 1
            state == AuthState.SignedIn -> 3
            else -> 2
        },
        label = "session",
    ) { screen ->
        when (screen) {
            0 -> UpdateRequiredScreen(requiredVersion)
            1 -> LaunchScreen()
            2 -> LoginScreen(onSignIn)
            else -> MainShell(links)
        }
    }
}

/** The five tabs, each with its own back stack, and the screens they push. */
@Composable
private fun MainShell(links: Flow<AppRoute>) {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.NOW) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    // Coming back to a tab's root (e.g. with Back) selects that tab.
    LaunchedEffect(destination) {
        AppTab.entries.firstOrNull { tab -> destination?.hasRoute(tab.route::class) == true }?.let { selectedTab = it }
    }

    fun selectTab(tab: AppTab) {
        if (tab == selectedTab) {
            navController.popBackStack(tab.route, inclusive = false)
        } else {
            navController.navigate(tab.route) {
                popUpTo(NowRoute) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        selectedTab = tab
    }

    val navigator = object : Navigator {
        override fun open(route: AppRoute) {
            navController.navigate(
                when (route) {
                    is AppRoute.VenueDetail -> VenueRoute(route.id)
                    is AppRoute.EventDetail -> EventRoute(route.id)
                    is AppRoute.PersonDetail -> PersonRoute(route.id)
                },
            )
        }

        override fun openChat(venueId: String, venueName: String) {
            navController.navigate(ChatRoute(venueId, venueName))
        }

        override fun back() {
            navController.popBackStack()
        }
    }

    // A link switches to its tab, back to the root, then opens the screen.
    LaunchedEffect(links) {
        links.collect { route ->
            val tab = route.tab
            navController.navigate(tab.route) {
                popUpTo(NowRoute) { saveState = tab != selectedTab }
                launchSingleTop = true
            }
            navController.popBackStack(tab.route, inclusive = false)
            selectedTab = tab
            navigator.open(route)
        }
    }

    AskForPermissions()

    val inChat = destination?.hasRoute(ChatRoute::class) == true

    Scaffold(
        containerColor = tokens.surface,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!inChat) {
                NavigationBar(containerColor = tokens.surfaceRaised, tonalElevation = 0.dp) {
                    AppTab.entries.forEach { tab -> TabItem(tab, selected = tab == selectedTab) { selectTab(tab) } }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = NowRoute, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable<NowRoute> { NowScreen(navigator) }
            composable<EventsRoute> { EventsScreen(navigator) }
            composable<VenuesRoute> { VenuesScreen(navigator) }
            composable<PeopleRoute> { PeopleScreen(navigator) }
            composable<MeRoute> { MeScreen() }
            composable<VenueRoute> { VenueScreen(it.toRoute<VenueRoute>().id, navigator) }
            composable<EventRoute> { EventScreen(it.toRoute<EventRoute>().id, navigator) }
            composable<PersonRoute> { PersonScreen(it.toRoute<PersonRoute>().id, navigator) }
            composable<ChatRoute> {
                val route = it.toRoute<ChatRoute>()
                VenueChatScreen(route.venueId, route.venueName, navigator)
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: AppTab, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(tab.icon, contentDescription = null) },
        label = { Text(tab.title, style = HotMessType.bodySmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = tokens.accentInk,
            selectedTextColor = tokens.accentInk,
            indicatorColor = tokens.accentSoft,
            unselectedIconColor = tokens.inkMuted,
            unselectedTextColor = tokens.inkMuted,
        ),
    )
}

/** After sign-in, asks once for location (and beacons and notifications), like the iOS app. */
@Composable
private fun AskForPermissions() {
    val graph = LocalAppGraph.current
    val access by graph.location.access.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.location.onPermissionResult()
    }

    LaunchedEffect(access) {
        when (access) {
            LocationAccess.NOT_REQUESTED -> launcher.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
            LocationAccess.ALLOWED -> graph.location.start()
            LocationAccess.DENIED -> Unit
        }
    }
}

/** A screen's top bar on the wash, with Back on pushed screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = tokens.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, style = HotMessType.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.surface,
                    scrolledContainerColor = tokens.surface,
                    titleContentColor = tokens.ink,
                    navigationIconContentColor = tokens.ink,
                    actionIconContentColor = tokens.ink,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}
