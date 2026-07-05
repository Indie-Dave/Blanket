package com.dnslock.family

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object PasswordDialog {

    fun showVerify(
        context: Context,
        title: String,
        onSuccess: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val passwordInput = createPasswordInput(context, R.string.password_hint)
        showDialog(context, title, passwordInput, onCancel) {
            if (PasswordManager.verifyPassword(context, passwordInput.text.toString())) {
                onSuccess()
            } else {
                Toast.makeText(context, R.string.password_incorrect, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showSetPassword(context: Context, onSuccess: () -> Unit) {
        if (PasswordManager.isPasswordSet(context)) {
            showVerify(context, context.getString(R.string.enter_current_password), onSuccess = {
                showNewPasswordEntry(context, onSuccess)
            })
        } else {
            showNewPasswordEntry(context, onSuccess)
        }
    }

    private fun showNewPasswordEntry(context: Context, onSuccess: () -> Unit) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val passwordInput = createPasswordInput(context, R.string.password_hint)
        val confirmInput = createPasswordInput(context, R.string.confirm_password_hint)
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        container.addView(passwordInput)
        container.addView(confirmInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = margin })

        val title = if (PasswordManager.isPasswordSet(context)) {
            context.getString(R.string.change_password)
        } else {
            context.getString(R.string.set_password)
        }

        showDialog(context, title, container, null) {
            val password = passwordInput.text.toString()
            val confirm = confirmInput.text.toString()
            when {
                !PasswordManager.isValidPassword(password) -> {
                    Toast.makeText(context, R.string.password_invalid, Toast.LENGTH_SHORT).show()
                }
                password != confirm -> {
                    Toast.makeText(context, R.string.password_mismatch, Toast.LENGTH_SHORT).show()
                }
                PasswordManager.setPassword(context, password) -> {
                    Toast.makeText(context, R.string.password_set_success, Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
        }
    }

    private fun showDialog(
        context: Context,
        title: String,
        content: android.view.View,
        onCancel: (() -> Unit)?,
        onConfirm: () -> Unit
    ) {
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        content.setPadding(padding, padding / 2, padding, 0)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.password_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
            .show()
    }

    private fun createPasswordInput(context: Context, hintRes: Int): EditText =
        EditText(context).apply {
            hint = context.getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(64))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
}
