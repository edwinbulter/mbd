import { useKeycloak } from '@react-keycloak/web'
import { Link } from 'react-router-dom'

export function NavBar() {
  const { keycloak, initialized } = useKeycloak()

  return (
    <nav className="bg-gray-800 text-white p-4 shadow-lg">
      <div className="container mx-auto flex justify-between items-center">
        <div className="flex items-center space-x-8">
          <Link to="/" className="text-xl font-bold">MBD Admin</Link>
          {keycloak.authenticated && (
            <div className="space-x-4">
              <Link to="/" className="hover:text-gray-300">Config</Link>
              <Link to="/funds" className="hover:text-gray-300">Funds</Link>
            </div>
          )}
        </div>
        {initialized ? (
          keycloak.authenticated ? (
            <button
              onClick={() => keycloak.logout()}
              className="bg-gray-700 hover:bg-gray-600 px-4 py-2 rounded transition"
            >
              Logout
            </button>
          ) : (
            <button
              onClick={() => keycloak.login()}
              className="bg-gray-700 hover:bg-gray-600 px-4 py-2 rounded transition"
            >
              Login
            </button>
          )
        ) : (
          <span className="text-gray-300">Loading...</span>
        )}
      </div>
    </nav>
  )
}
