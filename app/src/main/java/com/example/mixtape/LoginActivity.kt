package com.example.mixtape

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

/**
 * LoginActivity handles user authentication.
 * It supports standard Email/Password login through Firebase Auth and
 * social login via Google using FirebaseUI.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: MaterialButton
    private lateinit var googleSignInButton: MaterialButton
    private lateinit var registerLink: TextView

    companion object {
        private const val TAG = "LoginActivity"
    }

    /**
     * Activity result launcher for the FirebaseUI Google Sign-In flow.
     * This abstracts the complex OAuth logic into a single contract.
     */
    private val googleSignInLauncher = registerForActivityResult(
        FirebaseAuthUIActivityResultContract(),
    ) { res ->
        this.onGoogleSignInResult(res)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        // Redirect immediately if the user session is still active
        if (auth.currentUser != null) {
            Toast.makeText(
                this,
                "Welcome back, ${auth.currentUser?.displayName ?: auth.currentUser?.email}!",
                Toast.LENGTH_SHORT
            ).show()
            transactToNextScreen()
            return
        }

        initViews()
        setupClickListeners()
        setupRegisterLink()
    }

    private fun initViews() {
        usernameEditText = findViewById(R.id.usernameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        googleSignInButton = findViewById(R.id.googleSignInButton)
        registerLink = findViewById(R.id.registerLink)
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            loginWithEmail()
        }

        googleSignInButton.setOnClickListener {
            signInWithGoogle()
        }
    }

    /**
     * Configures the registration prompt as a clickable spannable string.
     * This provides a better UX than a separate "Register" button.
     */
    private fun setupRegisterLink() {
        val text = "Don't have an account? Register here"
        val spannableString = SpannableString(text)

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
                startActivity(intent)
            }
        }

        val start = text.indexOf("Register here")
        val end = start + "Register here".length

        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.red)),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        registerLink.text = spannableString
        registerLink.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Authenticates the user using Firebase Email/Password provider.
     * Includes basic client-side validation for improved responsiveness.
     */
    private fun loginWithEmail() {
        val email = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isEmpty()) {
            usernameEditText.error = "Email is required"
            usernameEditText.requestFocus()
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            usernameEditText.error = "Please enter a valid email"
            usernameEditText.requestFocus()
            return
        }

        if (password.isEmpty()) {
            passwordEditText.error = "Password is required"
            passwordEditText.requestFocus()
            return
        }

        loginButton.isEnabled = false
        loginButton.text = "Signing in..."

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                loginButton.isEnabled = true
                loginButton.text = "Login"

                if (task.isSuccessful) {
                    Log.d(TAG, "signInWithEmail:success")
                    Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT).show()
                    transactToNextScreen()
                } else {
                    Log.w(TAG, "signInWithEmail:failure", task.exception)
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
    }

    /**
     * Triggers the Google Sign-In flow using FirebaseUI.
     * This handles the underlying Google Play Services configuration and OAuth token exchange.
     */
    private fun signInWithGoogle() {
        val providers = arrayListOf(
            AuthUI.IdpConfig.GoogleBuilder().build()
        )

        val signInIntent = AuthUI.getInstance()
            .createSignInIntentBuilder()
            .setLogo(R.drawable.sharp_music_note_2_24)
            .setAvailableProviders(providers)
            .setTheme(R.style.Theme_MixTape)
            .setTosAndPrivacyPolicyUrls(
                "https://example.com/terms.html",
                "https://example.com/privacy.html"
            )
            .build()

        googleSignInLauncher.launch(signInIntent)
    }

    /**
     * Handles the result of the Google Sign-In flow.
     */
    private fun onGoogleSignInResult(result: FirebaseAuthUIAuthenticationResult) {
        val response = result.idpResponse
        if (result.resultCode == RESULT_OK) {
            val user = auth.currentUser
            Log.d(TAG, "signInWithGoogle:success")
            Toast.makeText(
                this,
                "Welcome to MixTape, ${user?.displayName}!",
                Toast.LENGTH_SHORT
            ).show()
            transactToNextScreen()
        } else {
            Log.w(TAG, "Google sign in failed", response?.error)
            Toast.makeText(this, "Google sign in failed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Navigates to the main playlist selection screen and finishes this activity
     * to prevent users from navigating back to the login screen.
     */
    private fun transactToNextScreen() {
        startActivity(Intent(this, PlaylistSelectionActivity::class.java))
        finish()
    }
}
