package com.agoodisman.pianobridge

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import com.agoodisman.pianobridge.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var lastInputID: Int? = null
    private var discordConnection: DiscordConnection? = null
    private var tokenFile: File? = null

    private var uiState = UIState.LOADING

    // coroutine scope for doing misc work
    private val coroutineScope = CoroutineScope(Dispatchers.Default)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawUI()

        // shared reference to saved discord login token
        tokenFile = File(filesDir, "token.txt")

        // check audio permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // we have permission, so start everything
            init()
        } else {
            // we don't have permission, ask for permission
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) {
                    // if permission was granted, we can go ahead and start everything
                    init()
                }
                // if permission wasn't granted, then don't do anything. the app will just be dead
                // but the buttons won't do anything so it won't crash.
            }.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // do self diagnostics and setup buttons
    private fun init() {
        val am = this.getSystemService<AudioManager>()
        if (am == null) {
            Log.e(MAIN_TAG, "audio manager couldn't be found")
            return
        }
        // determine preferred audio sample rate
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).toInt()

        Log.i(MAIN_TAG, "determined sample rate $sampleRate")

        // get the builtin mic id.
        // under normal functionality we let the phone pic the mic, so it goes to the wired input,
        // but when toggling to "talking mode", we want to force it to use an area mic
        val builtinMics = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter{ it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val builtinInputDevice = if (builtinMics.size > 1) builtinMics.first { it.address != "back" }.id else if (builtinMics.size == 1) builtinMics.first().id else -1

        Log.i(MAIN_TAG, "determined builtin mic id $builtinInputDevice")


        val me = this

        // handle Go button
        binding.startButton.setOnClickListener {
            when (uiState) {
                UIState.READY -> {
                    // if ready to start, start
                    disableUI()
                    coroutineScope.launch {
                        me.start(sampleRate)
                        drawUI()
                    }
                }
                UIState.STARTED -> {
                    // if in started state, start button becomes the stop button
                    uiState = UIState.STOPPED
                    drawUI()
                    coroutineScope.launch {
                        discordConnection!!.stopLinks()
                    }
                }
                UIState.STOPPED -> {
                    // if in stopped state, start button becomes the quit button
                    EngineDelegate.shutdown()
                    this.finishAndRemoveTask()
                }
                else -> Log.e(MAIN_TAG, "start button pressed in bad ui state $uiState")
            }
        }

        // handle toggling between normal mic and forced talking mic
        binding.toggleButton.setOnClickListener {
            coroutineScope.launch {
                me.toggleInput(sampleRate, builtinInputDevice)
            }
        }

        // handle saving / editing the token
        binding.tokenButton.setOnClickListener {
            when(uiState) {
                // if we're token editing already, then try to connect with the new token and save it
                UIState.TOKEN -> coroutineScope.launch {
                    me.tryToken(binding.tokenField.text.toString(), sampleRate)
                }
                // if we're already connected, hitting the button means go back to editing
                UIState.READY -> {
                    showStateTransition(getString(R.string.state_enter_token))
                    uiState = UIState.TOKEN
                    drawUI()
                }
                else -> Log.e(MAIN_TAG, "token button pressed in bad state $uiState")
            }
        }

        // try to load token from storage
        if (tokenFile!!.exists()) {
            val tokenStr = tokenFile!!.inputStream().bufferedReader().use { it.readText() }
            binding.tokenField.setText(tokenStr) // save to text field in case it doesn't work and we go back
            coroutineScope.launch {
                me.tryToken(tokenStr, sampleRate)
            }
        } else {
            showStateTransition(getString(R.string.state_enter_token))
            uiState = UIState.TOKEN
            drawUI()
        }
    }

    // attempt to connect to discord via Kord, and then update the UI appropriately
    // based on the result
    private suspend fun tryToken(tokenStr: String, sampleRate: Int) {
        showStateTransition(getString(R.string.state_connecting))
        disableUI()

        discordConnection = DiscordConnection.connectToDiscord(coroutineScope, tokenStr, sampleRate)

        if (discordConnection == null) {
            // if we fail, go back to token state. We have to do this even though we're probably already there
            // because we could be doing this on startup and haven't entered the ui state yet
            showStateTransition(getString(R.string.state_bad_token))
            uiState = UIState.TOKEN
            drawUI()
        } else {
            showStateTransition(getString(R.string.state_connected))
            // while we're here, save the token for future use
            tokenFile!!.outputStream().bufferedWriter().use { it.write(tokenStr) }
            // get the list of voice channel names to populate the dropdown
            val vcMap = discordConnection!!.getVoiceChannels()
            // go to ready state
            showStateTransition(getString(R.string.state_ready))
            uiState = UIState.READY
            drawUI()
            runOnUiThread {
                // actually populate the dropdown
                val vcSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, vcMap.keys.toList())
                vcSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.discordDropdown.adapter = vcSpinnerAdapter
            }
        }

    }

    // helper to update the status text
    private fun showStateTransition(state: String) {
        runOnUiThread {
            binding.statusText.text = state
        }
    }

    // run the main state machine to start up processing
    private suspend fun start(sampleRate: Int) {
        // start output processor to play audio

        // no id specified here. trust the system to configure its own device for the output
        var result = EngineDelegate.initializeEngineOutput(sampleRate, 2)//OpusCodec.channelCount.v)
        showStateTransition(if (result) getString(R.string.state_startup_1) else getString(R.string.state_startup_f1))
        if (!result) {
            return
        }

        // wait for output to actually happen
        result = EngineDelegate.awaitOutputInitialized()
        showStateTransition(if (result) getString(R.string.state_startup_2) else getString(R.string.state_startup_f2))
        if (!result) {
            return
        }

        // start discord uplink
        result = discordConnection!!.startUplink(binding.discordDropdown.selectedItem.toString(), EngineDelegate::retrieveUplinkData)
        showStateTransition(if (result) getString(R.string.state_startup_3) else getString(R.string.state_startup_f3))
        if (!result) {
            return
        }

        // start discord downlink now that we have an active conn (from the uplink) and a place for output to go (the output processor)
        coroutineScope.launch {
            discordConnection!!.startDownlink(EngineDelegate::provideDownlinkData)
        }

        // finally do input to provide both uplink and loopback

        // provide -1 as the ID here, which tells the system to configure its own device for the input as well
        result = EngineDelegate.initializeEngineInput(sampleRate, -1, 2)//OpusCodec.channelCount.v)
        showStateTransition(if (result) getString(R.string.state_startup_4) else getString(R.string.state_startup_f4))
        if (!result) {
            return
        }
        lastInputID = -1 // remember that we passed -1
        runOnUiThread {
            binding.sourceText.text = getText(R.string.mic_state_inferred)
        }

        // wait for input to actually happen
        result = EngineDelegate.awaitInputInitialized()
        showStateTransition(if (result) getString(R.string.state_running) else getString(R.string.state_startup_f5))
        if (!result) {
            return
        }

        // ui will be redrawn when this returns, but if we succeeded, we want to update it
        uiState = UIState.STARTED
    }

    // run the auxiliary state machine to toggle the input source
    private fun toggleInput(sampleRate: Int, builtinInputDevice: Int) {
        // if somehow the button is pressed before we've started an input, just do nothing
        if (lastInputID == null) {
            return
        }

        // stop processing input
        var result = EngineDelegate.stopEngineInput()
        showStateTransition(if (result) getString(R.string.state_toggle_1) else getString(R.string.state_toggle_f1))
        if (!result) {
            return
        }

        // wait for it to stop AND CLOSE
        result = EngineDelegate.awaitInputStopped()
        showStateTransition(if (result) getString(R.string.state_toggle_2) else getString(R.string.state_toggle_f2))
        if (!result) {
            return
        }

        // if we were in "pick your input" (-1) mode, now we hardcode the builtin device to get ambient mic
        // otherwise, if were already hardcoded, go back to -1. If there was no builtin device, then it will be -1 either way.
        val nextInputID = if (lastInputID == -1) builtinInputDevice else -1

        result = EngineDelegate.initializeEngineInput(sampleRate, nextInputID, 2)//OpusCodec.channelCount.v)
        showStateTransition(if (result) getString(R.string.state_toggle_3) else getString(R.string.state_toggle_f3))
        if (!result) {
            return
        }
        lastInputID = nextInputID
        runOnUiThread {
            binding.sourceText.text = if (lastInputID == -1) getText(R.string.mic_state_inferred) else getText(R.string.mic_state_builtin)
        }

        // wait for input to start again
        result = EngineDelegate.awaitInputInitialized()
        showStateTransition(if (result) getString(R.string.state_resumed) else getString(R.string.state_toggle_f4))
    }

    // pseudo-declarative button drawing. set everything being visible and/or enabled as necessary
    private fun drawUI() {
        runOnUiThread {
            when (uiState) {
                UIState.LOADING -> {
                    binding.tokenField.visibility = View.VISIBLE
                    binding.tokenField.isEnabled = false
                    binding.discordDropdown.visibility = View.INVISIBLE

                    binding.startButton.visibility = View.INVISIBLE
                    binding.sourceText.visibility = View.INVISIBLE
                    binding.toggleButton.visibility = View.INVISIBLE
                    binding.tokenButton.visibility = View.VISIBLE
                    binding.tokenButton.isEnabled = false
                    binding.tokenButton.text = getText(R.string.token_button_save)
                }
                UIState.TOKEN -> {
                    binding.tokenField.visibility = View.VISIBLE
                    binding.tokenField.isEnabled = true
                    binding.discordDropdown.visibility = View.INVISIBLE

                    binding.startButton.visibility = View.INVISIBLE
                    binding.sourceText.visibility = View.INVISIBLE
                    binding.toggleButton.visibility = View.INVISIBLE
                    binding.tokenButton.visibility = View.VISIBLE
                    binding.tokenButton.isEnabled = true
                    binding.tokenButton.text = getText(R.string.token_button_save)
                }
                UIState.READY -> {
                    binding.tokenField.visibility = View.INVISIBLE
                    binding.discordDropdown.visibility = View.VISIBLE
                    binding.discordDropdown.isEnabled = true

                    binding.startButton.visibility = View.VISIBLE
                    binding.startButton.isEnabled = true
                    binding.startButton.text = getText(R.string.start_button)
                    binding.sourceText.visibility = View.INVISIBLE
                    binding.toggleButton.visibility = View.INVISIBLE
                    binding.tokenButton.visibility = View.VISIBLE
                    binding.tokenButton.isEnabled = true
                    binding.tokenButton.text = getText(R.string.token_button_reenter)
                }
                UIState.STARTED -> {
                    binding.tokenField.visibility = View.INVISIBLE
                    binding.discordDropdown.visibility = View.VISIBLE
                    binding.discordDropdown.isEnabled = false

                    binding.startButton.visibility = View.VISIBLE
                    binding.startButton.isEnabled = true
                    binding.startButton.text = getText(R.string.stop_button)
                    binding.sourceText.visibility = View.VISIBLE
                    binding.toggleButton.visibility = View.VISIBLE
                    binding.toggleButton.isEnabled = true
                    binding.tokenButton.visibility = View.INVISIBLE
                }
                UIState.STOPPED -> {
                    binding.tokenField.visibility = View.INVISIBLE
                    binding.discordDropdown.visibility = View.VISIBLE
                    binding.discordDropdown.isEnabled = false

                    binding.startButton.visibility = View.VISIBLE
                    binding.startButton.text = getText(R.string.quit_button)
                    binding.sourceText.visibility = View.VISIBLE
                    binding.toggleButton.visibility = View.VISIBLE
                    binding.toggleButton.isEnabled = false
                    binding.tokenButton.visibility = View.INVISIBLE
                }
            }
        }
    }

    // helper to stop all user input when a button is pushed.
    // will be refreshed when drawUI is next called
    private fun disableUI() {
        runOnUiThread {
            binding.tokenField.isEnabled = false
            binding.discordDropdown.isEnabled = false
            binding.startButton.isEnabled = false
            binding.toggleButton.isEnabled = false
            binding.tokenButton.isEnabled = false
        }
    }

    // enumeration of the UI state machine
    enum class UIState {
        // haven't started yet
        LOADING,
        // entering discord bot token
        TOKEN,
        // picking channel and waiting to click start
        READY,
        // running, waiting to stop or toggle the mic source
        STARTED,
        // disconnected from discord, waiting to quit
        STOPPED
        // quitting doesn't have a state, we just exit
    }

    companion object {
        // Used to load the 'pianobridge' library on application startup.
        init {
            System.loadLibrary("pianobridge")
        }

        private const val MAIN_TAG = "pianobridge"
    }
}