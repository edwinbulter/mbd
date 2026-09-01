import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { customerApi } from '@/services/customerApi'

interface Fund {
  id: number
  name: string
  isin: string
  currentPrice: number
  currency: string
}

export default function Funds() {
  const navigate = useNavigate()
  const [funds, setFunds] = useState<Fund[]>([])
  const [account, setAccount] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [selectedFund, setSelectedFund] = useState<Fund | null>(null)
  const [quantity, setQuantity] = useState('1')
  const [buying, setBuying] = useState(false)
  const [message, setMessage] = useState({ text: '', type: '' })
  const [buyError, setBuyError] = useState('')
  const [tradeLimits, setTradeLimits] = useState<{
    maxTradeQuantity: number
    minTradeQuantity: number
  } | null>(null)

  useEffect(() => {
    const initPage = async () => {
      try {
        setLoading(true)
        const fundsRes = await customerApi.getFunds()
        setFunds(fundsRes.data)

        const userRes = await customerApi.getProfile()
        const accountsRes = await customerApi.getAccounts(userRes.data.id)
        if (accountsRes.data && accountsRes.data.length > 0) {
          setAccount(accountsRes.data[0])
        }

        // Fetch trade limits for client-side validation (UX only, not security)
        const limitsRes = await customerApi.getTradeLimits()
        setTradeLimits(limitsRes.data)
      } catch (error) {
        console.error('Failed to initialize funds page', error)
      } finally {
        setLoading(false)
      }
    }
    initPage()
  }, [])

  const handleBuy = async () => {
    if (!account || !selectedFund || !tradeLimits) return

    const qty = parseFloat(quantity)

    // Client-side validation for UX (backend enforces security)
    if (qty < tradeLimits.minTradeQuantity) {
      setBuyError(`Trade quantity must be at least ${tradeLimits.minTradeQuantity}`)
      return
    }
    if (qty > tradeLimits.maxTradeQuantity) {
      setBuyError(`Trade quantity exceeds maximum limit of ${tradeLimits.maxTradeQuantity.toLocaleString()}`)
      return
    }

    try {
      setBuying(true)
      setBuyError('')
      await customerApi.buyFund({
        accountId: account.id,
        fundId: selectedFund.id,
        quantity: qty,
        price: selectedFund.currentPrice
      })
      setMessage({ text: `Successfully bought ${quantity} shares of ${selectedFund.name}!`, type: 'success' })
      setSelectedFund(null)
      setQuantity('1')
      setTimeout(() => navigate('/'), 2000)
    } catch (error: any) {
      // Backend validation failed (real security control)
      const errorMsg = error.response?.data?.message || 'Transaction failed.'
      setMessage({ text: errorMsg, type: 'error' })
      setBuyError(errorMsg)
    } finally {
      setBuying(false)
    }
  }

  if (loading) return (
    <div className="flex justify-center items-center h-64">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
    </div>
  )

  return (
    <div className="container mx-auto p-4">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-3xl font-bold">Investment Funds</h1>
        {account && (
          <div className="text-right bg-white px-4 py-2 rounded shadow-sm border">
            <p className="text-xs text-gray-500 uppercase">Available Cash</p>
            <p className="font-bold text-green-600">€{account.balance.toFixed(2)}</p>
          </div>
        )}
      </div>

      {message.text && (
        <div className={`mb-6 p-4 rounded-md ${message.type === 'success' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'}`}>
          {message.text}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {funds.map((fund) => (
          <div key={fund.id} className="bg-white rounded-lg shadow-md overflow-hidden flex flex-col">
            <div className="p-6 flex-grow">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <h2 className="text-xl font-bold text-gray-900">{fund.name}</h2>
                  <p className="text-sm text-gray-500 font-mono">{fund.isin}</p>
                </div>
                <div className="bg-blue-100 text-blue-800 px-3 py-1 rounded text-sm font-semibold">
                  {fund.currency}
                </div>
              </div>
              <div className="text-3xl font-bold text-blue-600 mb-2">
                €{fund.currentPrice.toFixed(2)}
              </div>
            </div>
            <div className="p-4 bg-gray-50 border-t">
              <button
                onClick={() => setSelectedFund(fund)}
                className="w-full bg-blue-600 text-white py-2 rounded font-semibold hover:bg-blue-700 transition"
              >
                Buy Fund
              </button>
            </div>
          </div>
        ))}
      </div>

      {selectedFund && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex justify-center items-center z-50">
          <div className="bg-white p-8 rounded-lg shadow-xl w-full max-w-md">
            <h2 className="text-2xl font-bold mb-4 text-gray-900">Buy {selectedFund.name}</h2>
            <div className="mb-4">
              <p className="text-sm text-gray-500 mb-1">Current Price</p>
              <p className="text-xl font-bold text-blue-600">€{selectedFund.currentPrice.toFixed(2)}</p>
            </div>
            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-700 mb-1">Quantity</label>
              <input
                type="number"
                step="0.01"
                min={tradeLimits?.minTradeQuantity || 0.01}
                max={tradeLimits?.maxTradeQuantity || 10000}
                value={quantity}
                onChange={(e) => {
                  setQuantity(e.target.value)
                  setBuyError('')
                }}
                className="w-full border-gray-300 rounded-md shadow-sm focus:border-blue-500 focus:ring-blue-500 text-xl font-semibold"
              />
              {tradeLimits && (
                <p className="mt-1 text-xs text-gray-500">
                  Limits: {tradeLimits.minTradeQuantity} - {tradeLimits.maxTradeQuantity.toLocaleString()} shares
                </p>
              )}
              <p className="mt-2 text-sm text-gray-500">
                Total Cost: <span className="font-bold text-gray-900">€{(parseFloat(quantity) * selectedFund.currentPrice).toFixed(2)}</span>
              </p>
              {buyError && (
                <p className="mt-2 text-sm text-red-600 font-semibold">{buyError}</p>
              )}
            </div>
            <div className="flex space-x-4">
              <button
                onClick={handleBuy}
                disabled={buying || !tradeLimits || parseFloat(quantity) <= 0}
                className="flex-1 bg-blue-600 text-white py-2 rounded font-semibold hover:bg-blue-700 transition disabled:opacity-50"
              >
                {buying ? 'Processing...' : 'Confirm Purchase'}
              </button>
              <button
                onClick={() => {
                  setSelectedFund(null)
                  setBuyError('')
                }}
                className="flex-1 bg-gray-200 text-gray-700 py-2 rounded font-semibold hover:bg-gray-300 transition"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

