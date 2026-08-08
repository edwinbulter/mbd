import { useEffect, useState } from 'react'
import { customerApi } from '@/services/customerApi'
import { PortfolioDto } from '@/types/portfolio'

export default function Dashboard() {
  const [portfolio, setPortfolio] = useState<PortfolioDto | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchPortfolio = async () => {
      try {
        const response = await customerApi.getPortfolio(1) // Assuming accountId 1 for now
        setPortfolio(response.data)
      } catch (error) {
        console.error('Failed to fetch portfolio', error)
      } finally {
        setLoading(false)
      }
    }

    fetchPortfolio()
    const interval = setInterval(fetchPortfolio, 30000) // Poll every 30s
    return () => clearInterval(interval)
  }, [])

  if (loading) return <div>Loading dashboard...</div>

  return (
    <div className="container mx-auto p-4">
      <h1 className="text-3xl font-bold mb-6">MBD Dashboard</h1>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-white p-6 rounded-lg shadow-md border-t-4 border-blue-500">
          <h2 className="text-xl font-semibold mb-2">Total Value</h2>
          <p className="text-4xl font-bold text-blue-600">
            €{portfolio?.totalValue?.toFixed(2) || '0.00'}
          </p>
        </div>
        <div className="bg-white p-6 rounded-lg shadow-md border-t-4 border-green-500">
          <h2 className="text-xl font-semibold mb-2">Holdings</h2>
          <p className="text-4xl font-bold text-green-600">
            {portfolio?.holdings?.length || 0}
          </p>
        </div>
      </div>
    </div>
  )
}
