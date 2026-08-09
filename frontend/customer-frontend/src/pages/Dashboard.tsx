import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { customerApi } from '@/services/customerApi'
import { PortfolioDto } from '@/types/portfolio'

export default function Dashboard() {
  const navigate = useNavigate()
  const [user, setUser] = useState<any>(null)
  const [account, setAccount] = useState<any>(null)
  const [portfolio, setPortfolio] = useState<PortfolioDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [creatingAccount, setCreatingAccount] = useState(false)
  const [showDeposit, setShowDeposit] = useState(false)
  const [depositAmount, setDepositAmount] = useState('1000')

  const initDashboard = async () => {
    try {
      setLoading(true)
      // 1. Get user profile
      const userRes = await customerApi.getProfile()
      setUser(userRes.data)

      // 2. Get investment account
      const accountRes = await customerApi.getAccounts(userRes.data.id)
      if (accountRes.data && accountRes.data.length > 0) {
        const mainAccount = accountRes.data[0]
        setAccount(mainAccount)

        // 3. Get portfolio
        const portfolioRes = await customerApi.getPortfolio(mainAccount.id)
        setPortfolio(portfolioRes.data)
      }
    } catch (error: any) {
      if (error.response?.status === 404) {
        navigate('/register')
      } else {
        console.error('Dashboard init failed', error)
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    initDashboard()
  }, [navigate])

  const handleOpenAccount = async () => {
    if (!user) return
    try {
      setCreatingAccount(true)
      const res = await customerApi.createAccount({ userId: user.id })
      setAccount(res.data)
    } catch (error) {
      console.error('Failed to create account', error)
    } finally {
      setCreatingAccount(false)
    }
  }

  const handleDeposit = async () => {
    if (!account) return
    try {
      setLoading(true)
      await customerApi.deposit(account.id, parseFloat(depositAmount))
      setShowDeposit(false)
      await initDashboard()
    } catch (error) {
      console.error('Deposit failed', error)
    } finally {
      setLoading(false)
    }
  }

  if (loading && !portfolio) return (
    <div className="flex justify-center items-center h-64">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
    </div>
  )

  if (!account) {
    return (
      <div className="container mx-auto p-4 text-center">
        <div className="bg-white p-12 rounded-lg shadow-md max-w-lg mx-auto">
          <h2 className="text-2xl font-bold mb-4">Welcome, {user?.firstName}!</h2>
          <p className="text-gray-600 mb-8">
            You don't have an active investment account yet. Open one now to start trading.
          </p>
          <button
            onClick={handleOpenAccount}
            disabled={creatingAccount}
            className="bg-green-600 text-white py-3 px-8 rounded-lg font-semibold hover:bg-green-700 transition disabled:opacity-50"
          >
            {creatingAccount ? 'Opening Account...' : 'Open Investment Account'}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="container mx-auto p-4">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-3xl font-bold">Dashboard</h1>
        <div className="flex items-center space-x-4">
          <button
            onClick={() => setShowDeposit(true)}
            className="bg-green-600 text-white px-4 py-2 rounded hover:bg-green-700 transition"
          >
            Deposit Cash
          </button>
          <div className="text-right">
            <p className="text-sm text-gray-500 uppercase">Account Number</p>
            <p className="font-mono font-bold text-lg">{account.accountNumber}</p>
          </div>
        </div>
      </div>
      
      {showDeposit && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex justify-center items-center z-50">
          <div className="bg-white p-8 rounded-lg shadow-xl w-full max-w-md">
            <h2 className="text-2xl font-bold mb-4">Deposit Cash</h2>
            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-700 mb-1">Amount (€)</label>
              <input
                type="number"
                value={depositAmount}
                onChange={(e) => setDepositAmount(e.target.value)}
                className="w-full border-gray-300 rounded-md shadow-sm focus:border-blue-500 focus:ring-blue-500 text-2xl font-bold"
              />
            </div>
            <div className="flex space-x-4">
              <button
                onClick={handleDeposit}
                className="flex-1 bg-green-600 text-white py-2 rounded font-semibold hover:bg-green-700 transition"
              >
                Confirm Deposit
              </button>
              <button
                onClick={() => setShowDeposit(false)}
                className="flex-1 bg-gray-200 text-gray-700 py-2 rounded font-semibold hover:bg-gray-300 transition"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
      
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        <div className="bg-white p-6 rounded-lg shadow-md border-t-4 border-blue-500">
          <h2 className="text-sm font-semibold text-gray-500 uppercase mb-2">Total Value</h2>
          <p className="text-4xl font-bold text-blue-600">
            €{portfolio?.totalValue?.toFixed(2) || '0.00'}
          </p>
        </div>
        
        <div className="bg-white p-6 rounded-lg shadow-md border-t-4 border-green-500">
          <h2 className="text-sm font-semibold text-gray-500 uppercase mb-2">Cash Balance</h2>
          <p className="text-4xl font-bold text-green-600">
            €{account.balance?.toFixed(2) || '0.00'}
          </p>
        </div>

        <div className="bg-white p-6 rounded-lg shadow-md border-t-4 border-purple-500">
          <h2 className="text-sm font-semibold text-gray-500 uppercase mb-2">Holdings</h2>
          <p className="text-4xl font-bold text-purple-600">
            {portfolio?.holdings?.length || 0}
          </p>
        </div>
      </div>

      <div className="mt-8 bg-white rounded-lg shadow-md overflow-hidden">
        <div className="p-6 border-b">
          <h2 className="text-xl font-bold">My Holdings</h2>
        </div>
        <table className="w-full text-left">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase">Fund</th>
              <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase text-right">Quantity</th>
              <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase text-right">Avg Price</th>
              <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase text-right">Current Value</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {portfolio?.holdings?.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-6 py-12 text-center text-gray-500">
                  No holdings yet. Start by buying some funds!
                </td>
              </tr>
            ) : (
              portfolio?.holdings?.map((holding) => (
                <tr key={holding.id}>
                  <td className="px-6 py-4">
                    <div className="font-semibold text-gray-900">{holding.fundName || 'Unknown Fund'}</div>
                    <div className="text-sm text-gray-500">{holding.fundIsin || holding.fundId}</div>
                  </td>
                  <td className="px-6 py-4 text-right">{holding.quantity.toFixed(4)}</td>
                  <td className="px-6 py-4 text-right">€{holding.averagePrice.toFixed(2)}</td>
                  <td className="px-6 py-4 text-right font-bold text-blue-600">€{holding.currentValue.toFixed(2)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

