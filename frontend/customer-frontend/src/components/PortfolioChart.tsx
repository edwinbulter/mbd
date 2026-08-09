import { useEffect, useState } from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { customerApi } from '@/services/customerApi'

interface Snapshot {
  id: number | null
  accountId: number
  totalValue: number
  timestamp: string
}

export default function PortfolioChart({ accountId }: { accountId: number }) {
  const [data, setData] = useState<Snapshot[]>([])
  const [loading, setLoading] = useState(true)

  const fetchHistory = async () => {
    try {
      const res = await customerApi.getPortfolioHistory(accountId)
      setData(res.data)
    } catch (error) {
      console.error('Failed to fetch portfolio history', error)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchHistory()
    const interval = setInterval(fetchHistory, 60000)
    return () => clearInterval(interval)
  }, [accountId])

  if (loading) {
    return (
      <div className="bg-white p-6 rounded-lg shadow-md">
        <h2 className="text-xl font-bold mb-4">Portfolio Value History</h2>
        <div className="flex justify-center items-center h-48">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        </div>
      </div>
    )
  }

  if (data.length === 0) {
    return (
      <div className="bg-white p-6 rounded-lg shadow-md">
        <h2 className="text-xl font-bold mb-4">Portfolio Value History</h2>
        <p className="text-gray-500 text-center py-12">No history data yet. Price updates will populate this chart over time.</p>
      </div>
    )
  }

  const chartData = data.map(snap => ({
    time: new Date(snap.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    value: Number(snap.totalValue),
  }))

  return (
    <div className="bg-white p-6 rounded-lg shadow-md">
      <h2 className="text-xl font-bold mb-4">Portfolio Value History</h2>
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={chartData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
          <XAxis dataKey="time" tick={{ fontSize: 12 }} />
          <YAxis tick={{ fontSize: 12 }} domain={['auto', 'auto']} />
          <Tooltip
            formatter={(value: any) => [`€${Number(value).toFixed(2)}`, 'Total Value']}
            contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb' }}
          />
          <Line
            type="monotone"
            dataKey="value"
            stroke="#2563eb"
            strokeWidth={2}
            dot={{ r: 3 }}
            activeDot={{ r: 5 }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
