package com.preza.keeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.preza.keeper.ui.AppViewModel
import com.preza.keeper.ui.KeeperApp
import com.preza.keeper.ui.KeeperTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android shows its own confirmation before anything goes to the trash.
        val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            vm.onTrashResult(r.resultCode == RESULT_OK)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.trashRequests.collect { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            }
        }

        setContent {
            val st by vm.ui.collectAsStateWithLifecycle()
            KeeperTheme(st.appearance) { KeeperApp(vm) }
        }
    }
}
