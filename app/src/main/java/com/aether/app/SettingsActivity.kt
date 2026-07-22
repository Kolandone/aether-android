package com.aether.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.aether.app.core.ConfigManager
import com.aether.app.model.AetherConfig

class SettingsActivity : AppCompatActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var spinnerScan: Spinner
    private lateinit var spinnerIp: Spinner
    private lateinit var spinnerNoize: Spinner
    private lateinit var spinnerEch: Spinner
    private lateinit var etBind: EditText
    private lateinit var etPeer: EditText
    private lateinit var etTlsGroups: EditText
    private lateinit var switchQuickReconnect: Switch
    private lateinit var switchNoDataCheck: Switch
    private lateinit var layoutMasque: LinearLayout
    private lateinit var switchH2: Switch
    private lateinit var etH2Peer: EditText
    private lateinit var switchFragment: Switch
    private lateinit var etFragmentSize: EditText
    private lateinit var etFragmentDelay: EditText
    private lateinit var etValidateSecs: EditText
    private lateinit var etReconnectSecs: EditText
    private lateinit var layoutWg: LinearLayout
    private lateinit var etKeepalive: EditText
    private lateinit var etWgPeer: EditText
    private lateinit var switchNoProfileRetry: Switch
    private lateinit var etWgReconnectSecs: EditText

    private var currentProtocol = AetherConfig.Protocol.MASQUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configManager = ConfigManager(this)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        initViews()
        loadConfig()

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndFinish() }
    }

    private fun initViews() {
        spinnerScan = findViewById(R.id.spinnerScan)
        spinnerIp = findViewById(R.id.spinnerIp)
        spinnerNoize = findViewById(R.id.spinnerNoize)
        spinnerEch = findViewById(R.id.spinnerEch)
        etBind = findViewById(R.id.etBind)
        etPeer = findViewById(R.id.etPeer)
        etTlsGroups = findViewById(R.id.etTlsGroups)
        switchQuickReconnect = findViewById(R.id.switchQuickReconnect)
        switchNoDataCheck = findViewById(R.id.switchNoDataCheck)
        layoutMasque = findViewById(R.id.layoutMasque)
        switchH2 = findViewById(R.id.switchH2)
        etH2Peer = findViewById(R.id.etH2Peer)
        switchFragment = findViewById(R.id.switchFragment)
        etFragmentSize = findViewById(R.id.etFragmentSize)
        etFragmentDelay = findViewById(R.id.etFragmentDelay)
        etValidateSecs = findViewById(R.id.etValidateSecs)
        etReconnectSecs = findViewById(R.id.etReconnectSecs)
        layoutWg = findViewById(R.id.layoutWg)
        etKeepalive = findViewById(R.id.etKeepalive)
        etWgPeer = findViewById(R.id.etWgPeer)
        switchNoProfileRetry = findViewById(R.id.switchNoProfileRetry)
        etWgReconnectSecs = findViewById(R.id.etWgReconnectSecs)

        val scans = AetherConfig.ScanMode.entries.map { it.label }.toTypedArray()
        val ips = AetherConfig.IpVersion.entries.map { it.label }.toTypedArray()
        val noizeProfiles = arrayOf("firewall", "gfw", "off", "balanced", "aggressive", "light")
        val echOptions = arrayOf("off", "auto", "base64")

        spinnerScan.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scans)
        spinnerIp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ips)
        spinnerNoize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, noizeProfiles)
        spinnerEch.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, echOptions)

        switchH2.setOnCheckedChangeListener { _, checked ->
            switchFragment.isEnabled = checked
            if (!checked) {
                switchFragment.isChecked = false
                etFragmentSize.visibility = View.GONE
                etFragmentDelay.visibility = View.GONE
            }
        }
        switchFragment.setOnCheckedChangeListener { _, checked ->
            etFragmentSize.visibility = if (checked) View.VISIBLE else View.GONE
            etFragmentDelay.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun loadConfig() {
        val c = configManager.loadConfig()
        currentProtocol = c.protocol
        spinnerScan.setSelection(c.scanMode.ordinal)
        spinnerIp.setSelection(c.ipVersion.ordinal)
        etBind.setText(c.bindAddress)
        etPeer.setText(c.peer)
        etTlsGroups.setText(c.tlsGroups)
        switchQuickReconnect.isChecked = c.quickReconnect
        switchNoDataCheck.isChecked = c.noDataCheck

        val noizeIdx = listOf("firewall", "gfw", "off", "balanced", "aggressive", "light")
            .indexOf(c.noize).takeIf { it >= 0 } ?: 0
        spinnerNoize.setSelection(noizeIdx)

        switchH2.isChecked = c.useH2
        switchFragment.isChecked = c.fragment
        switchFragment.isEnabled = c.useH2
        etFragmentSize.setText(c.fragmentSize)
        etFragmentDelay.setText(c.fragmentDelay)
        etH2Peer.setText(c.h2Peer)
        spinnerEch.setSelection(when (c.ech) { "auto" -> 1; "base64" -> 2; else -> 0 })
        etValidateSecs.setText(c.validateSecs.toString())
        etReconnectSecs.setText(c.reconnectSecs.toString())

        etKeepalive.setText(c.keepalive.toString())
        etWgReconnectSecs.setText(c.wgReconnectSecs.toString())
        switchNoProfileRetry.isChecked = c.noProfileRetry
        etWgPeer.setText(c.wgPeer)

        etFragmentSize.visibility = if (c.fragment) View.VISIBLE else View.GONE
        etFragmentDelay.visibility = if (c.fragment) View.VISIBLE else View.GONE

        updateProtocolSections()
    }

    private fun updateProtocolSections() {
        val isMasque = currentProtocol == AetherConfig.Protocol.MASQUE
        layoutMasque.visibility = if (isMasque) View.VISIBLE else View.GONE
        layoutWg.visibility = if (isMasque) View.GONE else View.VISIBLE
        if (isMasque && spinnerNoize.selectedItemPosition >= 3) spinnerNoize.setSelection(0)
        if (!isMasque && spinnerNoize.selectedItemPosition < 3) spinnerNoize.setSelection(3)
    }

    private fun saveAndFinish() {
        val noizeValues = listOf("firewall", "gfw", "off", "balanced", "aggressive", "light")
        val c = AetherConfig(
            protocol = currentProtocol,
            scanMode = AetherConfig.ScanMode.entries[spinnerScan.selectedItemPosition],
            ipVersion = AetherConfig.IpVersion.entries[spinnerIp.selectedItemPosition],
            noize = noizeValues.getOrElse(spinnerNoize.selectedItemPosition) { "firewall" },
            useH2 = switchH2.isChecked,
            fragment = switchFragment.isChecked,
            fragmentSize = etFragmentSize.text.toString().ifEmpty { "16-32" },
            fragmentDelay = etFragmentDelay.text.toString().ifEmpty { "2-10" },
            h2Peer = etH2Peer.text.toString().trim(),
            ech = when (spinnerEch.selectedItemPosition) { 1 -> "auto"; 2 -> "base64"; else -> "" },
            validateSecs = etValidateSecs.text.toString().toIntOrNull() ?: 10,
            reconnectSecs = etReconnectSecs.text.toString().toIntOrNull() ?: 2,
            keepalive = etKeepalive.text.toString().toIntOrNull() ?: 5,
            wgReconnectSecs = etWgReconnectSecs.text.toString().toIntOrNull() ?: 2,
            noProfileRetry = switchNoProfileRetry.isChecked,
            wgPeer = etWgPeer.text.toString().trim(),
            quickReconnect = switchQuickReconnect.isChecked,
            noDataCheck = switchNoDataCheck.isChecked,
            peer = etPeer.text.toString().trim(),
            tlsGroups = etTlsGroups.text.toString().trim(),
            bindAddress = etBind.text.toString().trim().ifEmpty { "127.0.0.1:1819" }
        )
        configManager.saveConfig(c)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
