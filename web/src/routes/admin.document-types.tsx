import { createFileRoute } from "@tanstack/react-router";
import { DocumentTypeList } from "@/features/workflow";

export const Route = createFileRoute("/admin/document-types")({
  component: () => <DocumentTypeList />,
});
