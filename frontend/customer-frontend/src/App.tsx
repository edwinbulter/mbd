import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ReactKeycloakProvider } from '@react-keycloak/web'
import keycloak from '@/utils/keycloak'
import { ProtectedRoute } from '@/components/ProtectedRoute'
import Dashboard from '@/pages/Dashboard'

function App() {
  return (
    <ReactKeycloakProvider authClient={keycloak}>
      <BrowserRouter>
        <div className="min-h-screen bg-gray-100">
          <nav className="bg-blue-600 text-white p-4 shadow-lg">
            <div className="container mx-auto flex justify-between items-center">
              <span className="text-xl font-bold">MBD Customer</span>
              <button 
                onClick={() => keycloak.logout()}
                className="bg-blue-700 hover:bg-blue-800 px-4 py-2 rounded transition"
              >
                Logout
              </button>
            </div>
          </nav>
          <Routes>
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <Dashboard />
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
