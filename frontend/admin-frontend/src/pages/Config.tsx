import { useEffect, useState } from 'react'
import { adminApi } from '@/services/adminApi'

export default function Config() {
  const [config, setConfig] = useState({ volatility: 0.02, updateFrequencyMinutes: 5 })
  const [loading, setLoading] = useState(true)
  const [message, setMessage] = useState('')

  useEffect(() => {
    adminApi.getPriceUpdateConfig()
      .then(res => setConfig(res.data))
      .catch(err => console.error(err))
      .finally(() => setLoading(false))
  }, [])

  const handleSave = async () => {
    try {
      await adminApi.updatePriceUpdateConfig(config)
      setMessage('Configuration saved successfully!')
      setTimeout(() => setMessage(''), 3000)
    } catch (error) {
      setMessage('Failed to save configuration.')
    }
  }

  if (loading) return <div>Loading config...</div>

  return (
    <div className="container mx-auto p-4 max-w-lg">
      <h1 className="text-2xl font-bold mb-6">System Configuration</h1>
      <div className="bg-white p-6 rounded-lg shadow-md space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700">Volatility (%)</label>
          <input
            type="number"
            step="0.01"
            value={config.volatility}
            onChange={e => setConfig({ ...config, volatility: parseFloat(e.target.value) })}
            className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700">Update Frequency (minutes)</label>
          <input
            type="number"
            value={config.updateFrequencyMinutes}
            onChange={e => setConfig({ ...config, updateFrequencyMinutes: parseInt(e.target.value) })}
            className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500"
          />
        </div>
        <button
          onClick={handleSave}
          className="w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition"
        >
          Save Configuration
        </button>
        {message && <p className={`text-sm ${message.includes('success') ? 'text-green-600' : 'text-red-600'}`}>{message}</p>}
      </div>
    </div>
  )
}
