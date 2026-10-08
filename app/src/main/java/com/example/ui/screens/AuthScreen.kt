package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

@Composable
fun AuthScreen(
    onAuthenticated: (UserRole) -> Unit
) {
    val context = LocalContext.current
    var registerMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.STUDENT) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resetSent by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(3.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.padding(bottom = 10.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.padding(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        "درس‌یار",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "صوت کلاس را بدهید؛ متن، عنوان و خلاصه را بگیرید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuthTab(
                            title = "ورود",
                            selected = !registerMode,
                            modifier = Modifier.weight(1f)
                        ) { registerMode = false; error = null }
                        AuthTab(
                            title = "ثبت‌نام",
                            selected = registerMode,
                            modifier = Modifier.weight(1f)
                        ) { registerMode = true; error = null }
                    }

                    Spacer(Modifier.height(18.dp))

                    if (registerMode) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("نام و نام خانوادگی") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.height(10.dp))

                        Text(
                            "نقش کاربری",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(7.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoleButton("دانشجو", role == UserRole.STUDENT, Modifier.weight(1f)) {
                                role = UserRole.STUDENT
                            }
                            RoleButton("استاد", role == UserRole.TEACHER, Modifier.weight(1f)) {
                                role = UserRole.TEACHER
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ایمیل") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("رمز عبور") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    if (!registerMode) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                resetSent = false
                                error = null
                                loading = true
                                AuthPreferences.sendPasswordReset(
                                    context = context,
                                    email = email
                                ) { result ->
                                    loading = false
                                    result.fold(
                                        onSuccess = { resetSent = true },
                                        onFailure = { error = it.message ?: "ارسال ایمیل بازیابی انجام نشد." }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("بازیابی رمز عبور")
                        }
                    }

                    if (resetSent) {
                        Text(
                            "اگر این ایمیل در سامانه ثبت شده باشد، لینک تغییر رمز برای شما ارسال می‌شود.",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error!!,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val normalizedEmail = email.trim().lowercase()
                            error = null
                            resetSent = false
                            loading = true
                            if (registerMode) {
                                AuthPreferences.register(
                                    context = context,
                                    email = normalizedEmail,
                                    password = password,
                                    role = role,
                                    name = name.trim()
                                ) { result ->
                                    loading = false
                                    result.fold(
                                        onSuccess = { loggedRole -> onAuthenticated(loggedRole) },
                                        onFailure = { error = it.message ?: "ساخت حساب انجام نشد." }
                                    )
                                }
                            } else {
                                AuthPreferences.login(
                                    context = context,
                                    email = normalizedEmail,
                                    password = password
                                ) { result ->
                                    loading = false
                                    result.fold(
                                        onSuccess = { loggedRole -> onAuthenticated(loggedRole) },
                                        onFailure = { error = it.message ?: "ایمیل یا رمز عبور نادرست است." }
                                    )
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(if (loading) "در حال پردازش..." else if (registerMode) "ساخت حساب" else "ورود", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.weight(1f).height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                        Text("  یا  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(Modifier.weight(1f).height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                    }
                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            error = "ورود با گوگل پس از اتصال حساب Firebase فعال می‌شود."
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("ادامه با گوگل")
                    }

                    if (!registerMode) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "ورود با گوگل به‌صورت پیش‌فرض نقش دانشجو دارد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthTab(
    title: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(1.dp)
        ) { Text(title, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp)
        ) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RoleButton(
    title: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text(title) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp)
        ) { Text(title) }
    }
}
