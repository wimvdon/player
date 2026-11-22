package to.wim.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.wim.player.service.QueueService
import to.wim.player.ui.debug.DebugScreen
import to.wim.player.ui.debug.DebugViewModel
import to.wim.player.ui.generator.GeneratorScreen
import to.wim.player.ui.preview.PreviewScreen
import to.wim.player.ui.running.RunningScreen
import to.wim.player.ui.settings.SettingsScreen
import to.wim.player.ui.settings.SettingsViewModel
import to.wim.player.ui.theme.Motion
import to.wim.player.ui.theme.rememberReducedMotion

enum class Screen { GENERATOR, PREVIEW, QUEUE, SETTINGS, DEBUG }

/**
 * The screens as a small back stack, with the generator at the bottom. Five screens don't need a
 * navigation library. [openQueue] is set when the notification is tapped.
 */
@Composable
fun AppNavigation(
    openQueue: Boolean,
    onQueueOpened: () -> Unit,
) {
    val main: MainViewModel = viewModel()
    val context = LocalContext.current
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.GENERATOR.name)) }
    val current = Screen.valueOf(stack.last())

    fun push(screen: Screen) {
        stack = stack.filterNot { it == screen.name } + screen.name
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    LaunchedEffect(openQueue) {
        if (openQueue) {
            stack = listOf(Screen.GENERATOR.name, Screen.QUEUE.name)
            onQueueOpened()
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    val reduced = rememberReducedMotion()
    val offset = with(LocalDensity.current) { 30.dp.roundToPx() }

    AnimatedContent(
        targetState = current,
        // Screens are declared from the bottom of the stack up, so a higher one is "forward".
        transitionSpec = { Motion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal, offsetPx = if (reduced) 0 else offset) },
        label = "screens",
    ) { screen ->
        when (screen) {
            Screen.GENERATOR ->
                GeneratorScreen(
                    viewModel = main,
                    onOpenSettings = { push(Screen.SETTINGS) },
                    onOpenPreview = { push(Screen.PREVIEW) },
                    onOpenQueue = { push(Screen.QUEUE) },
                )
            Screen.PREVIEW ->
                PreviewScreen(
                    viewModel = main,
                    onBack = ::pop,
                    onQueueStarted = {
                        QueueService.start(context)
                        stack = listOf(Screen.GENERATOR.name, Screen.QUEUE.name)
                    },
                    onOpenSettings = { push(Screen.SETTINGS) },
                )
            Screen.QUEUE -> RunningScreen(viewModel = main, onBack = ::pop, onOpenSettings = { push(Screen.SETTINGS) })
            Screen.SETTINGS -> {
                val settings: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = settings,
                    onBack = {
                        main.refreshAfterSetup()
                        pop()
                    },
                    onOpenDebug = { push(Screen.DEBUG) },
                )
                BackHandler { main.refreshAfterSetup(); pop() }
            }
            Screen.DEBUG -> {
                val debug: DebugViewModel = viewModel()
                DebugScreen(viewModel = debug, onBack = ::pop)
            }
        }
    }
}
