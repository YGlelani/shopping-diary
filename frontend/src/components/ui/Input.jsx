export function Input({ className = '', ...props }) {
  return (
    <input
      className={`w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 transition-shadow ${className}`}
      {...props}
    />
  );
}