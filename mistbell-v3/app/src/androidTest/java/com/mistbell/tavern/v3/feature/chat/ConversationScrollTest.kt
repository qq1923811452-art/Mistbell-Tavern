package com.mistbell.tavern.v3.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val ready = mutableStateOf(false)
    private val scope = mutableStateOf("first")
    private val history = mutableStateListOf<ChatLine>()
    private lateinit var state: LazyListState

    @Test
    fun delayedHistoryReachesPixelBottomOfOversizedLastItem() {
        showFixture()
        compose.runOnIdle {
            history.add(line("long", LONG_TEXT))
            ready.value = true
        }
        awaitBottom()
        compose.runOnIdle { assertEquals(0, state.layoutInfo.visibleItemsInfo.last().index) }
    }

    @Test
    fun growthFollowsUntilUserScrollsAndButtonResumes() {
        loadLongHistory()
        compose.runOnIdle { history[0] = line("long", LONG_TEXT + LONG_TEXT) }
        awaitBottom()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var offset = 0
        compose.runOnIdle {
            assertTrue(state.canScrollForward)
            offset = state.firstVisibleItemScrollOffset
            history[0] = line("long", LONG_TEXT + LONG_TEXT + LONG_TEXT)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(offset, state.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("bottom").performClick()
        awaitBottom()
        compose.runOnIdle { history[0] = line("long", LONG_TEXT.repeat(REPEATS)) }
        awaitBottom()
    }

    @Test
    fun sendingWhileReadingHistoryActivelyReturnsToBottom() {
        loadLongHistory()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.canScrollForward)
            history.add(line("sent", "new message", "user"))
            history.add(line("reply", "stream"))
        }
        awaitBottom()
    }

    @Test
    fun switchingScopeDiscardsPositionAndWaitsForNewHistory() {
        loadLongHistory()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val previous = state
        compose.runOnIdle {
            ready.value = false
            scope.value = "next"
            history.clear()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(previous !== state)
            history.add(line("next", LONG_TEXT))
            ready.value = true
        }
        awaitBottom()
    }

    private fun loadLongHistory() {
        showFixture()
        compose.runOnIdle {
            history.add(line("long", LONG_TEXT))
            ready.value = true
        }
        awaitBottom()
    }

    private fun showFixture() {
        compose.setContent { fixture() }
    }

    @Composable
    private fun fixture() {
        key(scope.value) {
            state = rememberLazyListState()
            val scroll = rememberConversationScroll(state, ready.value, history)
            Column {
                Box(Modifier.height(VIEWPORT_HEIGHT.dp).nestedScroll(scroll.connection)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("messages"), state = state) {
                        items(if (ready.value) history.toList() else emptyList()) { Text(it.content) }
                    }
                }
                if (scroll.showReturnToBottom) {
                    TextButton(scroll.onReturnToBottom, Modifier.testTag("bottom")) { Text("bottom") }
                }
            }
        }
    }

    private fun awaitBottom() {
        compose.waitUntil {
            state.layoutInfo.totalItemsCount == history.size &&
                state.layoutInfo.visibleItemsInfo.isNotEmpty() && !state.canScrollForward
        }
        compose.waitForIdle()
    }

    private fun line(
        id: String,
        content: String,
        role: String = "assistant",
    ) = ChatLine(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 0L,
        deliveryState = PersistedChatMessage.DeliveryState.COMPLETE,
    )

    private companion object {
        const val VIEWPORT_HEIGHT = 180
        const val REPEATS = 4
        val LONG_TEXT = (1..100).joinToString("\n") { "line $it" }
    }
}
