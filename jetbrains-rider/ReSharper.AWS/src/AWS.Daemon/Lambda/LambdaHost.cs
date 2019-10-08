using System.Collections.Generic;
using System.Linq;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Host.Features;
using JetBrains.ReSharper.Host.Platform.Icons;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.Modules;
using JetBrains.ReSharper.Resources.Shell;
using JetBrains.Rider.Model;

namespace ReSharper.AWS.Lambda
{
    [SolutionComponent]
    public class LambdaHost
    {
        private readonly LambdaModel myModel;
        private readonly ISymbolCache mySymbolCache;
        private readonly PsiIconManager myPsiIconManager;
        private readonly IconHost myIconHost;

        public LambdaHost(ISolution solution, ISymbolCache symbolCache, PsiIconManager psiIconManager, IconHost iconHost)
        {
            myModel = solution.GetProtocolSolution().GetLambdaModel();
            mySymbolCache = symbolCache;
            myPsiIconManager = psiIconManager;
            myIconHost = iconHost;

            myModel.DetermineHandlers.Set((lifetime, unit) =>
            {
                var task = new RdTask<List<HandlerCompletionItem>>();
                task.Set(DetermineHandlers(solution));
                return task;
            });
        }

        public void RunLambda(string methodName, string handler)
        {
            myModel.RunLambda(new LambdaRequest(methodName, handler));
        }

        public void DebugLambda(string methodName, string handler)
        {
            myModel.DebugLambda(new LambdaRequest(methodName, handler));
        }

        public void CreateNewLambda(string methodName, string handler)
        {
            myModel.CreateNewLambda(new LambdaRequest(methodName, handler));
        }

        private List<HandlerCompletionItem> DetermineHandlers(ISolution solution)
        {
            var handlers = new List<HandlerCompletionItem>();

            using (ReadLockCookie.Create())
            {
                var projects = solution.GetAllProjects();

                foreach (var project in projects)
                {
                    if (!LambdaFinder.IsLambdaProjectType(project)) continue;
                    var psiModules = project.GetPsiModules();

                    foreach (var psiModule in psiModules)
                    {
                        using (CompilationContextCookie.OverrideOrCreate(psiModule.GetContextFromModule()))
                        {
                            var scope = mySymbolCache.GetSymbolScope(psiModule, false, true);
                            var namespaces = scope.GlobalNamespace.GetNestedNamespaces(scope);

                            foreach (var @namespace in namespaces)
                            {
                                ProcessNamespace(@namespace, scope, handlers);
                            }
                        }
                    }
                }
            }

            return handlers;
        }

        private void ProcessNamespace(INamespace element, ISymbolScope scope,
            ICollection<HandlerCompletionItem> handlers)
        {
            var nestedNamespaces = element.GetNestedNamespaces(scope);

            foreach (var @namespace in nestedNamespaces)
            {
                ProcessNamespace(@namespace, scope, handlers);
            }

            var classes = element.GetNestedElements(scope).OfType<IClass>();
            foreach (var @class in classes)
            {
                ProcessClass(@class, handlers);
            }
        }

        private void ProcessClass(ITypeElement element, ICollection<HandlerCompletionItem> handlers)
        {
            var nestedClasses = element.GetMembers().OfType<IClass>();

            foreach (var @class in nestedClasses)
            {
                ProcessClass(@class, handlers);
            }

            var methods = element.Methods;
            foreach (var method in methods)
            {
                GetHandlers(method, handlers);
            }
        }

        private void GetHandlers(IMethod method, ICollection<HandlerCompletionItem> handlers)
        {
            if (!LambdaFinder.IsSuitableLambdaMethod(method)) return;

            var handlerString = LambdaHandlerUtils.ComposeHandlerString(method);
            var iconId = myPsiIconManager.GetImage(method.GetElementType());
            var iconModel = myIconHost.Transform(iconId);
            handlers.Add(new HandlerCompletionItem(handlerString, iconModel));
        }
    }
}
