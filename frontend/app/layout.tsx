import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "커머스 운영 OS", description: "복수 사업자·판매계정 커머스 통합 운영관리" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&display=swap" />
      </head>
      <body>{children}</body>
    </html>
  );
}
