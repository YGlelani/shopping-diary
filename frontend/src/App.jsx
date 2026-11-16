import { useState } from 'react';
import Login from './components/Login';
import Dashboard from './pages/Dashboard';

export default function App() {
  const [token, setToken] = useState(localStorage.getItem('token') || null);
  const [email, setEmail] = useState('tester@example.com');

  const handleLogout = () => {
    setToken(null);
    localStorage.removeItem('token');
  };

  if (!token) return <Login onLogin={setToken} />;

  return <Dashboard token={token} email={email} onLogout={handleLogout} />;
}