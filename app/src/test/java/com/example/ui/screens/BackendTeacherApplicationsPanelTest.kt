package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendTeacherApplicationsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun approvedTeacherDoesNotSeeApplicationFormOrAdminQueue() {
        composeRule.setContent {
            MaterialTheme {
                BackendTeacherApplicationsPanel(
                    accountRoles = listOf("teacher"),
                    onStatus = {}
                )
            }
        }

        composeRule.onNodeWithText(
            "حساب شما در حال حاضر دسترسی استاد دارد؛ نیازی به ثبت درخواست جدید نیست."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("ثبت درخواست استاد").assertDoesNotExist()
        composeRule.onNodeWithText("درخواست‌های در انتظار بررسی").assertDoesNotExist()
    }

    @Test
    fun studentCanSeeApplicationFormButNotAdminQueue() {
        composeRule.setContent {
            MaterialTheme {
                BackendTeacherApplicationsPanel(
                    accountRoles = listOf("student"),
                    onStatus = {}
                )
            }
        }

        composeRule.onNodeWithText("حوزه تدریس").assertIsDisplayed()
        composeRule.onNodeWithText("ثبت درخواست استاد").assertIsNotEnabled()
        composeRule.onNodeWithText("درخواست‌های در انتظار بررسی").assertDoesNotExist()

        composeRule.onNodeWithText("حوزه تدریس").performTextInput("فقه")
        composeRule.onNodeWithText("ثبت درخواست استاد").assertIsEnabled()
    }
}
