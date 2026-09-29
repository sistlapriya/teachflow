package com.teachflow.agent.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.Question
import com.teachflow.agent.core.Reply

/**
 * Small dialog for a free answer to a mid-run question ("Which pizza would you like?" → "Farmhouse").
 * It runs in its own task, so finishing it returns the user to the app being automated.
 * Voice uses the system speech recogniser; typing always works.
 */
class AnswerActivity : Activity() {

    private lateinit var input: EditText
    private var qid = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        qid = intent.getLongExtra(EXTRA_ID, 0L)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = intent.getStringExtra(EXTRA_HEADLINE) ?: "TeachFlow"; textSize = 12f; alpha = 0.7f })
        root.addView(TextView(this).apply { text = intent.getStringExtra(EXTRA_TEXT) ?: ""; textSize = 17f; setPadding(0, pad / 3, 0, pad / 2) })
        input = EditText(this).apply {
            hint = "Your answer"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { send(); true } else false }
        }
        root.addView(input)
        intent.getStringArrayExtra(EXTRA_OPTIONS)?.forEach { o ->
            root.addView(Button(this).apply { text = o; isAllCaps = false; setOnClickListener { input.setText(o.removeSuffix(" (taught)")) } })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        row.addView(Button(this).apply { text = "\uD83C\uDF99 Speak"; setOnClickListener { speak() } })
        row.addView(Button(this).apply { text = "Send"; setOnClickListener { send() } })
        root.addView(row)
        setContentView(root)
        title = ""
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) { input.error = "Type or say an answer"; return }
        AgentBus.answer(qid, Reply.Text(t))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, intent.getStringExtra(EXTRA_TEXT))
        try { startActivityForResult(i, REQ_SPEECH) } catch (e: ActivityNotFoundException) { input.error = "Speech recognition isn't available. Please type." }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input.setText(it); send() }
        }
    }

    companion object {
        private const val REQ_SPEECH = 7
        private const val EXTRA_ID = "qid"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_HEADLINE = "headline"
        private const val EXTRA_OPTIONS = "options"

        fun intent(context: Context, q: Question): Intent = Intent(context, AnswerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ID, q.id)
            .putExtra(EXTRA_TEXT, q.text)
            .putExtra(EXTRA_HEADLINE, q.headline)
            .putExtra(EXTRA_OPTIONS, q.options.toTypedArray())
    }
}
