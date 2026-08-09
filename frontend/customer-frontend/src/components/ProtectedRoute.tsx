import { useKeycloak } from '@react-keycloak/web'
import { Navigate } from 'react-router-dom'
import { ReactNode } from 'react'

interface ProtectedRouteProps {
  children: ReactNode
  requiredRole?: string
}

export function ProtectedRoute({ children, requiredRole }: ProtectedRouteProps) {
  const { keycloak, initialized } = useKeycloak()

  if (!initialized) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500"></div>
      </div>
    )
  }

  if (!keycloak.authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="bg-white p-8 rounded-lg shadow-md text-center">
          <h1 className="text-2xl font-bold mb-4">Welcome to MBD</h1>
          <p className="text-gray-600 mb-6">Please log in to access your account.</p>
          <button
            onClick={() => keycloak.login()}
            className="bg-blue-600 text-white px-6 py-3 rounded font-semibold hover:bg-blue-700 transition"
          >
            Log In
          </button>
        </div>
      </div>
    )
  }

  if (requiredRole && !keycloak.hasRealmRole(requiredRole)) {
    return <Navigate to="/unauthorized" />
  }

  return <>{children}</>
}
