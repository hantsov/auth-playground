import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider } from './auth/AuthProvider';
import { AccountProvider } from './auth/AccountProvider';
import HomePage from './pages/HomePage';
import DashboardPage from './pages/DashboardPage';
import InspectorPage from './pages/InspectorPage';
import RegisterPage from './pages/RegisterPage';
import ProtectedRoute from './components/ProtectedRoute';

const AuthLoading = () => (
  <div className="min-h-full flex items-center justify-center bg-zinc-50">
    <p className="text-sm text-zinc-500">Loading…</p>
  </div>
);

const App = () => {
  return (
    <AuthProvider fallback={<AuthLoading />}>
      <AccountProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route
              path="/register"
              element={
                <ProtectedRoute allowUnregistered>
                  <RegisterPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <DashboardPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/inspector"
              element={
                <ProtectedRoute>
                  <InspectorPage />
                </ProtectedRoute>
              }
            />
          </Routes>
        </BrowserRouter>
      </AccountProvider>
    </AuthProvider>
  );
};

export default App;
