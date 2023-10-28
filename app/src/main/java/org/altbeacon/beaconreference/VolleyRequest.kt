package org.altbeacon.beaconreference

import android.content.Context
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject

class VolleyRequest(private val context: Context) {
    private val URL = "http://81.171.29.53"

    private val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(context)
    }

    fun sendPostRequest(url: String, jsonBody: JSONObject, onResponseListener: (JSONObject) -> Unit, onErrorListener: (String) -> Unit) {
        val jsonObjectRequest = object : JsonObjectRequest(
            Request.Method.POST,
            url,
            jsonBody,
            Response.Listener { response ->
                onResponseListener(response)
            },
            Response.ErrorListener { error ->
                onErrorListener(error.message ?: "An error occurred")
            }) {

            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>()
                headers["Authorization"] = "Bearer Aj7EdmehoKUU8SRLMKX2SgmpjD7QlAnt40xwRenjy3AESxwDFvdGogQ10f9lL6ccBcU32VfIsLagy4wxQf8AumQF3wI8U0vpoi6sXVhFg0KjW9A58lwToNA7Q5H665Y8XwCTXXY15hQCqrb5YulmWgtsd4mbCRjinHm4qN0hTeariTeJneOCIfiSxLMfSrrmMAnQwENcPYgBdGaIFOmJEBBcOKDQya7cnPloLyCGHHJtsMOzJ18ITeMGzb0AkxUbjdZXBWbChX3bSYWDNxWxzjJYleJsxvNSPWEd9GQJBlncS8iDuqq7XG9whXOrE7OxnHvrLugaLzg0PJjTSSAh29LTJDYh8IyDZiW1JzntJGoWhpYngi76r9BQKfm8Jv9SWwKnARPgzYLGxEGAjJutr5nvUIODVCiixiGqCPkx0wcJJDSwyWOEVYLsJlgZNBvj6sSz4D0C6afGJuViPWN3QHMk04lAXEggEP6D51GE8hl4kp0I9F5G"
                headers["Content-Type"] = "application/json"
                headers["X-Requested-With"] = "XMLHttpRequest"

                return headers
            }
        }

        requestQueue.add(jsonObjectRequest)
    }
}