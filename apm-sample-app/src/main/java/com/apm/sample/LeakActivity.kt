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

    /** ViewModel instance retained to demonstrate explicit [com.apm.memory.MemoryModule.checkViewModel] inspection. */
    private lateinit var leakViewModel: LeakViewModel

    /** Initializes deliberate Activity and ViewModel leak fixtures for sample testing. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leak)

        // Deliberate leak: store Activity reference in a static field
        leakedActivity = this

        // Also create a ViewModel that holds Context (another leak pattern)
        leakViewModel = ViewModelProvider(this).get(LeakViewModel::class.java)
        leakViewModel.attachContext(this)

        findViewById<Button>(R.id.finishButton).setOnClickListener {
            finish()
        }
    }

    /** Triggers explicit ViewModel leak inspection when the host Activity finishes. */
    override fun onDestroy() {
        if (isFinishing && ::leakViewModel.isInitialized) {
            val sampleApp = application as? SampleApplication
            if (sampleApp != null && sampleApp.soakSdkEnabled) {
                sampleApp.memoryModule.checkViewModel(leakViewModel)
            }
        }
        super.onDestroy()
    }

    /**
     * ViewModel that deliberately holds a Context reference.
     */
    class LeakViewModel : ViewModel() {
        /** Deliberately retained Activity context reference used to trigger ViewModel leak detection. */
        private var context: android.content.Context? = null

        /** Binds a Context reference onto this ViewModel for leak testing. */
        fun attachContext(ctx: android.content.Context) {
            context = ctx
        }
    }

    companion object {
        /** Static strong reference retaining the destroyed Activity instance on purpose. */
        @JvmStatic
        var leakedActivity: LeakActivity? = null
    }
}
