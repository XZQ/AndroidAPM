package com.apm.sample

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Deliberately leaking Activity.
 * Static [leakedActivity] holds a strong reference after destroy.
 */
class LeakActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leak)

        // Deliberate leak: store Activity reference in a static field
        leakedActivity = this

        // Also create a ViewModel that holds Context (another leak pattern)
        val leakViewModel = ViewModelProvider(this).get(LeakViewModel::class.java)
        leakViewModel.attachContext(this)

        findViewById<Button>(R.id.finishButton).setOnClickListener {
            finish()
        }
    }

    /**
     * ViewModel that deliberately holds a Context reference.
     */
    class LeakViewModel : ViewModel() {
        private var context: android.content.Context? = null

        fun attachContext(ctx: android.content.Context) {
            context = ctx
        }
    }

    companion object {
        @JvmStatic
        var leakedActivity: LeakActivity? = null
    }
}
