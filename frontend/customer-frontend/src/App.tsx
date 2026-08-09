import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ReactKeycloakProvider } from '@react-keycloak/web'
import keycloak from '@/utils/keycloak'
import { ProtectedRoute } from '@/components/ProtectedRoute'
import { NavBar } from '@/components/NavBar'
import Dashboard from '@/pages/Dashboard'
import Register from '@/pages/Register'
import Funds from '@/pages/Funds'

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
                <ProtectedRoute>
                  <Dashboard />
                </ProtectedRoute>
              }
            />
            <Route
              path="/register"
              element={
                <ProtectedRoute>
                  <Register />
                </ProtectedRoute>
              }
            />
            <Route
              path="/funds"
              element={
                <ProtectedRoute>
                  <Funds />
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
