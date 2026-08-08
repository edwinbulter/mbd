import Keycloak from 'keycloak-js'

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.mbd.local',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'mbd',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'admin-frontend',
})

export default keycloak
