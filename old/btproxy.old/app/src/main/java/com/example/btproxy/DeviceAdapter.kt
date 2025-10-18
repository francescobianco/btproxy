package com.example.btproxy

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val selectedDevices: Set<String>,
    private val onDeviceSelected: (BluetoothDevice, Boolean) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.deviceName)
        val deviceAddress: TextView = view.findViewById(R.id.deviceAddress)
        val deviceCheckbox: CheckBox = view.findViewById(R.id.deviceCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        
        try {
            holder.deviceName.text = device.name ?: "Unknown Device"
            holder.deviceAddress.text = device.address
            holder.deviceCheckbox.isChecked = selectedDevices.contains(device.address)
            
            holder.deviceCheckbox.setOnCheckedChangeListener { _, isChecked ->
                onDeviceSelected(device, isChecked)
            }
            
            holder.itemView.setOnClickListener {
                holder.deviceCheckbox.isChecked = !holder.deviceCheckbox.isChecked
            }
        } catch (e: SecurityException) {
            holder.deviceName.text = "Permission Required"
            holder.deviceAddress.text = device.address
        }
    }

    override fun getItemCount() = devices.size
}