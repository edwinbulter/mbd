import { useKeycloak } from '@react-keycloak/web'
import { Link } from 'react-router-dom'

export function NavBar() {
  const { keycloak, initialized } = useKeycloak()

  return (
    <nav className="bg-blue-600 text-white p-4 shadow-lg">
      <div className="container mx-auto flex justify-between items-center">
        <div className="flex items-center space-x-8">
          <Link to="/" className="text-xl font-bold">MBD Customer</Link>
          {keycloak.authenticated && (
            <div className="space-x-4">
              <Link to="/" className="hover:text-blue-200 transition">Dashboard</Link>
              <Link to="/funds" className="hover:text-blue-200 transition">Funds</Link>
            </div>
          )}
        </div>
        {initialized ? (
          keycloak.authenticated ? (
            <button
              onClick={() => keycloak.logout()}
              className="bg-blue-700 hover:bg-blue-800 px-4 py-2 rounded transition"
            >
              Logout
            </button>
          ) : (
            <button
              onClick={() => keycloak.login()}
              className="bg-blue-700 hover:bg-blue-800 px-4 py-2 rounded transition"
            >
              Login
            </button>
          )
        ) : (
          <span className="text-blue-200">Loading...</span>
        )}
      </div>
    </nav>
  )
}
