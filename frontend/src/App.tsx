import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import AppLayout from "./components/layout/AppLayout";
import DashboardPage from "./pages/DashboardPage";
import RequestsPage from "./pages/RequestsPage";
import TeamsPage from "./pages/TeamsPage";
import DispatcherWorkspacePage from "./pages/DispatcherWorkspacePage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/dispatch" replace />} />
          <Route path="overview" element={<DashboardPage />} />
          <Route path="requests" element={<RequestsPage />} />
          <Route path="teams" element={<TeamsPage />} />
          <Route path="dispatch" element={<DispatcherWorkspacePage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
