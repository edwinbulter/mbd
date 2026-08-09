import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ReactKeycloakProvider } from '@react-keycloak/web'
import keycloak from '@/utils/keycloak'
import { ProtectedRoute } from '@/components/ProtectedRoute'
import { NavBar } from '@/components/NavBar'
import Config from '@/pages/Config'
import AdminFunds from '@/pages/AdminFunds'

function App() {
  return (
    <ReactKeycloakProvider
      authClient={keycloak}
      initOptions={{ onLoad: 'check-sso', checkLoginIframe: false, pkceMethod: 'S256' }}
    >
      <BrowserRouter>
        <div className="min-h-screen bg-gray-100">
          <NavBar />
          <Routes>
            <Route
              path="/"
              element={
                <ProtectedRoute requiredRole="admin">
                  <Config />
                </ProtectedRoute>
              }
            />
            <Route
              path="/funds"
              element={
                <ProtectedRoute requiredRole="admin">
                  <AdminFunds />
                </ProtectedRoute>
              }
            />
            <Route path="/unauthorized" element={<div>Unauthorized Access</div>} />
          </Routes>
        </div>
      </BrowserRouter>
    </ReactKeycloakProvider>
  )
}

export default App
