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
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var confirmPasswordEditText: EditText
    private lateinit var registerButton: MaterialButton
    private lateinit var loginLink: TextView

    companion object {
        private const val TAG = "RegisterActivity"
        private const val FIRESTORE_TIMEOUT_SECONDS = 10L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Initialize Firebase Auth and Firestore
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Check if user is already logged in
        if (auth.currentUser != null) {
            transactToNextScreen()
            return
        }

        initViews()
        setupClickListeners()
        setupLoginLink()
    }

    private fun initViews() {
        usernameEditText = findViewById(R.id.usernameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText)
        registerButton = findViewById(R.id.registerButton)
        loginLink = findViewById(R.id.loginLink)
    }

    private fun setupClickListeners() {
        registerButton.setOnClickListener {
            registerUser()
        }
    }

    private fun setupLoginLink() {
        val text = "Already have an account? Login here"
        val spannableString = SpannableString(text)

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(this@RegisterActivity, LoginActivity::class.java)
                startActivity(intent)
                finish()
            }
        }

        val start = text.indexOf("Login here")
        val end = start + "Login here".length

        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.red)),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        loginLink.text = spannableString
        loginLink.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun registerUser() {
        val email = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        val confirmPassword = confirmPasswordEditText.text.toString().trim()

        // Validation
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

        if (password.length < 6) {
            passwordEditText.error = "Password must be at least 6 characters"
            passwordEditText.requestFocus()
            return
        }

        if (confirmPassword.isEmpty()) {
            confirmPasswordEditText.error = "Please confirm your password"
            confirmPasswordEditText.requestFocus()
            return
        }

        if (password != confirmPassword) {
            confirmPasswordEditText.error = "Passwords do not match"
            confirmPasswordEditText.requestFocus()
            return
        }

        registerButton.isEnabled = false
        registerButton.text = "Creating Account..."

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "createUserWithEmail:success")
                    val user = auth.currentUser

                    // Update UI to show Firestore step
                    registerButton.text = "Setting up profile..."

                    // Save user data to Firestore with timeout
                    user?.let {
                        createUserProfile(it.uid, email)
                    } ?: run {
                        // Fallback if user is null
                        handleRegistrationComplete()
                    }
                } else {
                    Log.w(TAG, "createUserWithEmail:failure", task.exception)
                    registerButton.isEnabled = true
                    registerButton.text = "Register"

                    val errorMessage = when {
                        task.exception?.message?.contains("email address is already in use") == true ->
                            "This email is already registered. Please use a different email or login."
                        task.exception?.message?.contains("weak password") == true ->
                            "Password is too weak. Please choose a stronger password."
                        else -> "Registration failed: ${task.exception?.message}"
                    }

                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun createUserProfile(uid: String, email: String) {
        val userData = hashMapOf(
            "email" to email,
            "displayName" to email.substringBefore("@"),
            "createdAt" to com.google.firebase.Timestamp.now(),
            "playlists" to emptyList<String>()
        )

        db.collection("users")
            .document(uid)
            .set(userData)
            .addOnSuccessListener {
                Log.d(TAG, "User profile created successfully")
                handleRegistrationComplete()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Error creating user profile", e)
                // Even if Firestore fails, authentication succeeded
                // So we can still proceed, but show a warning
                Toast.makeText(
                    this,
                    "Account created, but profile setup had issues. You can still use the app.",
                    Toast.LENGTH_LONG
                ).show()
                handleRegistrationComplete()
            }
    }

    private fun handleRegistrationComplete() {
        registerButton.isEnabled = true
        registerButton.text = "Register"

        Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
        transactToNextScreen()
    }

    private fun transactToNextScreen() {
        startActivity(Intent(this, PlaylistSelectionActivity::class.java))
        finish()
    }
}