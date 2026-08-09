import { useEffect, useState } from 'react'
import { adminApi } from '@/services/adminApi'

interface Fund {
  id: number
  name: string
  isin: string
  currentPrice: number
  volatility: number
  updateFrequencyMinutes: number
}

export default function AdminFunds() {
  const [funds, setFunds] = useState<Fund[]>([])
  const [loading, setLoading] = useState(true)
  const [showAddForm, setShowAddForm] = useState(false)
  const [newFund, setNewFund] = useState({
    name: '',
    isin: '',
    currentPrice: 100.0,
    currency: 'EUR',
    volatility: 0.02,
    updateFrequencyMinutes: 5
  })

  const fetchFunds = async () => {
    try {
      setLoading(true)
      const res = await adminApi.getFunds()
      setFunds(res.data)
    } catch (error) {
      console.error('Failed to fetch funds', error)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchFunds()
  }, [])

  const handleCreateFund = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await adminApi.createFund(newFund)
      setShowAddForm(false)
      fetchFunds()
    } catch (error) {
      console.error('Failed to create fund', error)
    }
  }

  const handleDeleteFund = async (id: number) => {
    if (!confirm('Are you sure you want to delete this fund?')) return
    try {
      await adminApi.deleteFund(id)
      fetchFunds()
    } catch (error) {
      console.error('Failed to delete fund', error)
    }
  }

  if (loading && funds.length === 0) return <div>Loading...</div>

  return (
    <div className="container mx-auto p-4">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Fund Management</h1>
        <button
          onClick={() => setShowAddForm(true)}
          className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700"
        >
          Add New Fund
        </button>
      </div>

      {showAddForm && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex justify-center items-center z-50">
          <div className="bg-white p-8 rounded-lg shadow-xl w-full max-w-md">
            <h2 className="text-xl font-bold mb-4">Add New Fund</h2>
            <form onSubmit={handleCreateFund} className="space-y-4">
              <div>
                <label className="block text-sm font-medium">Fund Name</label>
                <input
                  type="text"
                  required
                  value={newFund.name}
                  onChange={e => setNewFund({...newFund, name: e.target.value})}
                  className="w-full border rounded p-2"
                />
              </div>
              <div>
                <label className="block text-sm font-medium">ISIN</label>
                <input
                  type="text"
                  required
                  maxLength={12}
                  value={newFund.isin}
                  onChange={e => setNewFund({...newFund, isin: e.target.value})}
                  className="w-full border rounded p-2"
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium">Initial Price</label>
                  <input
                    type="number"
                    step="0.01"
                    required
                    value={newFund.currentPrice}
                    onChange={e => setNewFund({...newFund, currentPrice: parseFloat(e.target.value)})}
                    className="w-full border rounded p-2"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium">Volatility (%)</label>
                  <input
                    type="number"
                    step="0.01"
                    required
                    value={newFund.volatility}
                    onChange={e => setNewFund({...newFund, volatility: parseFloat(e.target.value)})}
                    className="w-full border rounded p-2"
                  />
                </div>
              </div>
              <div className="flex space-x-4">
                <button type="submit" className="flex-1 bg-green-600 text-white py-2 rounded font-bold">Create</button>
                <button type="button" onClick={() => setShowAddForm(false)} className="flex-1 bg-gray-200 py-2 rounded font-bold">Cancel</button>
              </div>
            </form>
          </div>
        </div>
      )}

      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full">
          <thead className="bg-gray-50 border-b">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Fund</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Price</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Volatility</th>
              <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {funds.map(fund => (
              <tr key={fund.id}>
                <td className="px-6 py-4">
                  <div className="font-bold">{fund.name}</div>
                  <div className="text-sm text-gray-500 font-mono">{fund.isin}</div>
                </td>
                <td className="px-6 py-4 text-right font-bold">€{fund.currentPrice.toFixed(2)}</td>
                <td className="px-6 py-4 text-right">{(fund.volatility * 100).toFixed(1)}%</td>
                <td className="px-6 py-4 text-right">
                  <button onClick={() => handleDeleteFund(fund.id)} className="text-red-600 hover:text-red-900 ml-4 font-bold">Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
