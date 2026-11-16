import { useState } from 'react';
import { apiRequest } from '../api/client';

export default function Login({ onLogin }) {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('tester@example.com');
  const [password, setPassword] = useState('TestPass123!');
  const [error, setError] = useState('');

  const handleAuth = async (e) => {
    e.preventDefault();
    setError('');
    try {
      const endpoint = isRegister ? '/auth/register' : '/auth/login';
      const body = isRegister ? { email, password, displayName: 'Test User' } : { email, password };
      const data = await apiRequest(endpoint, { method: 'POST', body: JSON.stringify(body) });
      localStorage.setItem('token', data.token);
      onLogin(data.token);
    } catch (err) { setError(err.message); }
  };

  return (
    <div className="min-h-screen bg-gray-100 flex items-center justify-center p-4">
      <form onSubmit={handleAuth} className="bg-white p-8 rounded-xl shadow-md w-full max-w-sm">
        <h1 className="text-2xl font-bold mb-6 text-gray-800 text-center">{isRegister ? 'Create Account' : 'Shopping Diary'}</h1>
        {error && <p className="text-red-500 text-sm mb-4">{error}</p>}
        <input type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="Email" className="w-full mb-3 p-2 border rounded" required />
        <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="Password" className="w-full mb-4 p-2 border rounded" required />
        <button type="submit" className="w-full bg-blue-600 text-white p-2 rounded hover:bg-blue-700 transition mb-4">{isRegister ? 'Sign Up' : 'Log In'}</button>
        <div className="text-center text-sm text-gray-500">
          {isRegister ? 'Already have an account? ' : "Don't have an account? "}
          <button type="button" onClick={() => { setIsRegister(!isRegister); setError(''); }} className="text-blue-600 hover:underline">{isRegister ? 'Log In' : 'Register'}</button>
        </div>
      </form>
    </div>
  );
}