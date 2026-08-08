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
    keycloak.login()
    return null
  }

  if (requiredRole && !keycloak.hasRealmRole(requiredRole)) {
    return <Navigate to="/unauthorized" />
  }

  return <>{children}</>
}
