import type { ReactElement } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { router } from "./router";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Server (the operation's revision) is the source of truth; avoid
      // silently serving stale operation state across screens.
      refetchOnWindowFocus: true,
      retry: 1,
    },
  },
});

export function App(): ReactElement {
  return (
    <QueryClientProvider client={queryClient}>
      <div className="ops-app-shell">
        <div className="ops-app-content">
          <RouterProvider router={router} />
        </div>
      </div>
    </QueryClientProvider>
  );
}
