package com.example.smartdoorlock.ui.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdoorlock.R

/**
 * 블루투스 기기 스캔 결과를 RecyclerView에 표시하는 어댑터
 * [수정] 이름 없는 기기도 표시 ("이름 없음" 또는 MAC 주소로 표시)
 */
class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]

        // [핵심 수정] 이름이 없으면 "이름 없음" 또는 MAC 주소 표시
        val deviceName = device.name
        if (deviceName != null && deviceName.isNotEmpty()) {
            holder.name.text = deviceName
        } else {
            // 이름이 없을 때
            holder.name.text = "이름 없음"
            holder.name.setTextColor(0xFF888888.toInt()) // 회색으로 표시
        }

        holder.address.text = device.address

        holder.itemView.setOnClickListener {
            onClick(device)
        }
    }

    override fun getItemCount() = devices.size
}