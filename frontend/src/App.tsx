import { BrowserRouter, Route, Routes } from "react-router-dom";
import AppLayout from "./components/layout/AppLayout";
import DashboardPage from "./pages/DashboardPage";
import RequestsPage from "./pages/RequestsPage";
import TeamsPage from "./pages/TeamsPage";
import DispatchPage from "./pages/DispatchPage";

export default function App() {
  return (
      <BrowserRouter>
        <Routes>
          <Route element={<AppLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="requests" element={<RequestsPage />} />
            <Route path="teams" element={<TeamsPage />} />
            <Route path="dispatch" element={<DispatchPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
  );
}