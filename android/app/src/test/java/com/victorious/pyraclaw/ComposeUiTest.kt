package com.victorious.pyraclaw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import com.victorious.pyraclaw.data.Approval
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.ui.approval.ApprovalPhase
import com.victorious.pyraclaw.ui.approval.ApprovalSheetContent
import com.victorious.pyraclaw.ui.approval.ApprovalUi
import com.victorious.pyraclaw.ui.components.LocalNow
import com.victorious.pyraclaw.ui.PC_POWER_REQUEST
import com.victorious.pyraclaw.ui.dashboard.DashboardScreen
import com.victorious.pyraclaw.ui.dashboard.DashboardUi
import com.victorious.pyraclaw.ui.help.HelpScreen
import com.victorious.pyraclaw.ui.theme.PyraTheme
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun wakeButtonCallsWake() {
        var wakes = 0
        rule.setContent {
            PyraTheme {
                CompositionLocalProvider(LocalNow provides Sample.NOW_MS) {
                    DashboardScreen(
                        DashboardUi(link = LinkState.Connected(0), status = Sample.status), Sample.NOW_MS,
                        onRefresh = {}, onWake = { wakes++ }, onPowerButton = {}, onRunTimer = {}, onOpenDevices = {}, onOpenTimers = {},
                    )
                }
            }
        }
        rule.onNodeWithText("WAKE PC").assertIsDisplayed()
        rule.onNodeWithTag("wakeButton").performClick()
        assertEquals(1, wakes)
        rule.onNodeWithTag("dashboard").performScrollToNode(androidx.compose.ui.test.hasText("esp32-wake-01"))
        rule.onNodeWithText("esp32-wake-01").assertIsDisplayed()
    }

    @Test fun holdToConfirmNeedsAFullHold() {
        var holds = 0
        rule.setContent { PyraTheme { ApprovalSheetContent(ApprovalUi(PC_POWER_REQUEST), { holds++ }, {}, {}) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("holdToConfirm").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("holdToConfirm").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals("a short tap must not approve", 0, holds)
    }

    @Test fun armedSheetShowsCountdownAndSends() {
        var sends = 0
        val ui = ApprovalUi(PC_POWER_REQUEST, ApprovalPhase.Armed(Approval("pc.power_pulse", "t", 30, 0), 17))
        rule.setContent { PyraTheme { ApprovalSheetContent(ui, {}, { sends++ }, {}) } }
        rule.onNodeWithText("17").assertIsDisplayed()
        rule.onNodeWithTag("approvalSend").performClick()
        assertEquals(1, sends)
    }

    @Test fun helpSearchFilters() {
        rule.setContent { PyraTheme { HelpScreen() } }
        rule.onNodeWithTag("helpSearch").performTextInput("403")
        rule.onNodeWithText("Troubleshooting").assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Getting started")).fetchSemanticsNodes().isEmpty())
    }
}
